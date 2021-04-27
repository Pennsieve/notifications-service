package com.pennsieve.notifications.api

import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import akka.{ Done, NotUsed }

import akka.stream.stage.{
  AsyncCallback,
  GraphStage,
  GraphStageLogic,
  OutHandler
}
import com.pennsieve.core.utilities.PostgresDatabase
import com.pennsieve.notifications.MessageType._
import com.pennsieve.notifications.api.NotificationWebServer.DIContainer
import com.pennsieve.notifications.api.db.notifications
import com.pennsieve.notifications._
import com.pennsieve.traits.PostgresProfile.api._
import com.typesafe.scalalogging.StrictLogging
import io.circe.syntax._
import io.circe.parser._

import com.impossibl.postgres.api.jdbc.{ PGConnection, PGNotificationListener }
import java.sql.DriverManager

import scala.concurrent.{ ExecutionContext, Future }

object PostgresPubSub extends StrictLogging {

  /**
    * Send notifications to Postgres pub-sub channel
    */
  def postgresNotify(
    insecureContainer: DIContainer,
    channelName: String
  )(implicit
    ec: ExecutionContext
  ): Flow[String, String, NotUsed] = {

    Flow[String]
      .mapAsyncUnordered(1)(payload => {
        logger.debug(s"Channel $channelName sending payload $payload")
        insecureContainer.db
          .run(
            sql"""SELECT pg_notify($channelName, $payload)"""
              .as[String]
          )
          .map(_ => payload)
      })
  }

  /**
    * Listen on Postgres channel for async notifications.
    */
  def postgresListen(
    insecureContainer: DIContainer,
    channelName: String
  ): Source[String, NotUsed] =
    Source.fromGraph(
      new PostgresListenSource(insecureContainer.postgresDatabase, channelName)
    )

  /**
    * Custom graph stage wrapping Postgres notification channel
    */
  class PostgresListenSource(postgres: PostgresDatabase, channelName: String)
      extends GraphStage[SourceShape[String]] {

    val out: Outlet[String] = Outlet("PostgresListenSource")
    override val shape: SourceShape[String] = SourceShape(out)

    // All mutable state must be in the GraphStageLogic
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        private val buffer: java.util.ArrayDeque[String] =
          new java.util.ArrayDeque[String]()

        val connection = asyncPostgresConnection(postgres)

        override def preStart(): Unit = {

          val successCallback: AsyncCallback[String] =
            getAsyncCallback[String](handleSuccess)

          val failureCallback: AsyncCallback[Exception] =
            getAsyncCallback[Exception](handleFailure)

          // Interface to handle async callbacks from PG connection
          // See http://impossibl.github.io/pgjdbc-ng/docs/current/user-guide/#extensions-notifications
          val listener = new PGNotificationListener() {
            override def notification(
              processId: Int,
              channelName: String,
              payload: String
            ) {
              logger.debug(s"Channel $channelName got payload $payload")
              successCallback.invoke(payload)
            }

            override def closed(): Unit = {
              failureCallback.invoke(
                new Exception("Postgres connection closed unexpectedly")
              )
            }
          }

          // Subscribe listener
          connection.addNotificationListener(listener)

          // Start listening on channel
          val statement = connection.createStatement();
          statement.executeUpdate(s"LISTEN $channelName");
          statement.close()
        }

        /**
          * Process a message received from the Postgres pub-sub channel.
          *
          * Push downstream if downstream is pulling, otherwise store in
          * internal buffer.
          */
        def handleSuccess(result: String): Unit = {
          (buffer.isEmpty, isAvailable(out)) match {
            case (false, true) =>
              push(out, buffer.poll())
              buffer.offer(result)
            case (true, true) =>
              push(out, result)
            case (_, false) =>
              buffer.offer(result)
          }
        }

        def handleFailure(ex: Exception): Unit = {
          connection.close()
          failStage(ex)
        }

        /**
          * Provide element from internal buffer
          */
        setHandler(
          out,
          new OutHandler {
            override def onPull(): Unit = {
              if (!buffer.isEmpty) {
                push(out, buffer.poll())
              }
            }

            override def onDownstreamFinish(cause: Throwable): Unit = {
              connection.close()
              super.onDownstreamFinish(cause)
            }
          }
        )
      }
  }

  /**
    * Create Postgres driver using alternate JDBC connection library that allows
    * complete event-driven notifications.
    *
    * See http://impossibl.github.io/
    */
  private def asyncPostgresConnection(
    postgres: PostgresDatabase
  ): PGConnection = {

    // Load driver
    Class.forName("com.impossibl.postgres.jdbc.PGDriver")

    val jdbcUrl: String = {
      val base =
        s"jdbc:pgsql://${postgres.host}:${postgres.port}/${postgres.database}"

      if (postgres.useSSL) base + "?ssl.mode=verify-ca&ssl.ca.certificate.file=/home/pennsieve/.postgresql/root.crt"
      else base
    }

    DriverManager
      .getConnection(jdbcUrl, postgres.user, postgres.password)
      .unwrap(classOf[PGConnection]);
  }
}
