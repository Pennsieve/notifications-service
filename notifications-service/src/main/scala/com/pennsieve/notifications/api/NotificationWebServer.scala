// Copyright (c) 2017 Pennsieve All Rights Reserved.

package com.pennsieve.notifications.api

import akka.actor.ActorSystem
import akka.stream.scaladsl.Keep
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.pathPrefix
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.RouteConcatenation._
import software.amazon.awssdk.services.sqs.model.{ Message => SQSMessage }
import com.pennsieve.akka.http.{ HealthCheck, HealthCheckService }
import com.pennsieve.aws.queue.{
  AWSSQSContainer,
  LocalSQSContainer,
  SQSContainer
}
import com.pennsieve.aws.cognito._
import com.pennsieve.core.utilities._
import com.pennsieve.domain.CoreError
import com.pennsieve.notifications.NotificationMessage
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.config.{ Config, ConfigFactory }
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Failure

object NotificationWebServer extends App with StrictLogging {

  type DIContainer =
    InsecureContainer
      with InsecureCoreContainer
      with DatabaseContainer
      with SQSContainer
      with RedisContainer
      with CognitoContainer

  implicit lazy val system: ActorSystem = ActorSystem("notifications")
  implicit lazy val executionContext: ExecutionContext =
    system.dispatcher

  val config: Config = ConfigFactory.load()

  val port: Int = config.getOrElse[Int]("port", 8080)
  val host: String = config.getOrElse[String]("host", "0.0.0.0")
  val environment: String = config.as[String]("environment")

  val isLocal: Boolean = environment.toLowerCase == "local"

  val insecureContainer: DIContainer =
    if (isLocal) {
      new InsecureContainer(config) with InsecureCoreContainer
      with DatabaseContainer with LocalSQSContainer with RedisContainer
      with LocalCognitoContainer
    } else {
      new InsecureContainer(config) with InsecureCoreContainer
      with DatabaseContainer with AWSSQSContainer with RedisContainer
      with AWSCognitoContainer
    }

  val healthCheck = new HealthCheckService(
    Map(
      "postgres" -> HealthCheck.postgresHealthCheck(insecureContainer.db),
      "redis" -> HealthCheck.redisHealthCheck(insecureContainer.redisClientPool)
    )
  )

  val notificationService = new NotificationService(insecureContainer, config)

  val routes: Route =
    Route.seal(healthCheck.routes ~ pathPrefix("notification") {
      notificationService.routes
    })

  val killswitch = new SQSSource(config, insecureContainer).wsMessageSource
    .toMat(notificationService.notificationStream.notificationSink)(Keep.left)
    .run()

  logger.info("Started notification stream")

  /**
    * If the notification stream dies (eg, because of a persistent Redis publish
    * error) kill the service.
    */
  notificationService.notificationStream.notificationSinkComplete.onComplete {
    case Failure(e) =>
      logger.error("Stream error", e)
      throw e
    case _ => ()
  }

  sys.addShutdownHook {
    logger.info("Shutting down stream")
    killswitch.shutdown()
  }

  Http().bindAndHandle(routes, host, port)
  logger.info(s"Server online at http://$host:$port")

  Await.result(system.whenTerminated, Duration.Inf)
}
