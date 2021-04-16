package com.pennsieve.notifications.api

import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl._
import akka.NotUsed
import com.pennsieve.notifications._
import com.pennsieve.core.utilities.UserAuthContext

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

case class InvalidSession(msg: String) extends Throwable
case class TimeoutException(msg: String) extends Throwable

/**
  * Flow that fails with a timeout if it has not seen a `Pong` message within
  * the given time period. All other messages are passed through.
  */
object PongMonitor {

  def apply(
    timeout: FiniteDuration,
    authContext: UserAuthContext
  ): Flow[NotificationMessage, NotificationMessage, NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>
          import GraphDSL.Implicits._

          val partitionPongs =
            builder.add(new Partition[NotificationMessage](2, {
              case p: Pong => 0
              case _ => 1
            }, eagerCancel = true))

          val idleTimeout =
            builder.add(Flow[NotificationMessage].idleTimeout(timeout))

          // If the session sent in the Pong is different from the one the
          // connection was opened with, we cannot be sure that the user should be
          // getting these notifications. Throw and error to drop the connection
          // and force a reconnect.
          val checkSession = builder.add(Flow[NotificationMessage].map { m =>
            authContext.cognitoPayload match {
              case Some(payload) if payload.expiresAt.isAfter(Instant.now()) =>
                m
              case _ => throw InvalidSession("Expired session")
            }
          })

          val drop = builder.add(Flow[NotificationMessage].filter(_ => false))

          val merge = builder.add(Merge[NotificationMessage](2))

          // @formatter:off

        partitionPongs ~> idleTimeout ~> drop ~> merge
        partitionPongs                                        ~> merge

        // @formatter:on

          FlowShape(partitionPongs.in, merge.out)
      })
      .mapError {
        // raised by idleTimeout
        case _: java.util.concurrent.TimeoutException =>
          TimeoutException(s"No PONG received in the last $timeout")
      }
}
