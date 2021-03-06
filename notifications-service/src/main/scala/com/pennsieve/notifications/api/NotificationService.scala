// Copyright (c) 2019 Pennsieve All Rights Reserved.

package com.pennsieve.notifications.api

import java.time.Instant

import akka.actor.{ ActorSystem, Cancellable }
import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.stream._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{ Done, NotUsed }
import cats.data.EitherT
import cats.implicits._
import com.pennsieve.akka.http.RouteService
import com.pennsieve.akka.http.directives.AuthorizationDirectives._
import com.pennsieve.auth.middleware.Jwt
import com.pennsieve.core.utilities.FutureEitherHelpers.implicits._
import com.pennsieve.core.utilities.UserAuthContext
import com.pennsieve.domain
import com.pennsieve.domain.Error
import com.pennsieve.models.User
import com.pennsieve.notifications.MessageType._
import com.pennsieve.notifications.api.NotificationWebServer.DIContainer
import com.pennsieve.notifications.api.db.notifications
import com.pennsieve.notifications.{ NotificationMessage, _ }
import com.pennsieve.notifications._
import com.pennsieve.traits.PostgresProfile.api._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.circe.generic.semiauto._
import io.circe.syntax._
import net.ceedubs.ficus.Ficus._
import org.mdedetrich.akka.http.support.CirceHttpSupport._
import org.mdedetrich.akka.stream.support.CirceStreamSupport

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Try }

class NotificationStream(
  insecureContainer: DIContainer
)(implicit
  system: ActorSystem,
  executionContext: ExecutionContext
) extends LazyLogging {

  val config = insecureContainer.config

  val pingInterval = config.as[Int]("notifications.pingInterval")
  val freshnessThreshold = config.as[Int]("notifications.freshnessThreshold")
  val aggregationInterval = config.as[Int]("notifications.aggregationInterval")
  val aggregationCount = config.as[Int]("notifications.aggregationCount")

  val systemMessageTypes = List(PingT, PongT, KeepAliveT)
  val messageTypesToToast = List(JobDone, Mention, DatasetUpdate)

  val postgresChannel = config.as[String]("postgres.notificationChannel")

  val minBackoff = config.as[FiniteDuration]("notifications.retry.minBackoff")
  val maxBackoff = config.as[FiniteDuration]("notifications.retry.maxBackoff")
  val randomFactor = config.as[Double]("notifications.retry.randomFactor")
  val maxRestarts = config.as[Int]("notifications.retry.maxRestarts")

  /**
    * Sink that publishes notification messages to the Postgres pub/sub broker.
    * These will be distributed to all other `notification-service` instances.
    */
  lazy val (
    notificationSink: Sink[NotificationMessage, NotUsed],
    notificationSinkComplete: Future[Done]
  ) = MergeHub
    .source[NotificationMessage]
    .via(CirceStreamSupport.encode[NotificationMessage])
    .via(
      RestartFlow.onFailuresWithBackoff(
        minBackoff = minBackoff,
        maxBackoff = maxBackoff,
        randomFactor = randomFactor,
        maxRestarts = maxRestarts
      )(() => {
        logger.info(s"Starting sink on Postgres channel '$postgresChannel'")
        PostgresPubSub.postgresNotify(insecureContainer, postgresChannel)
      })
    )
    .toMat(Sink.ignore)(Keep.both)
    .run()

  /**
    * Source that reads notification messages from the Postgres pub/sub broker.
    */
  lazy val notificationSource: Source[NotificationMessage, NotUsed] =
    RestartSource
      .onFailuresWithBackoff(
        minBackoff = minBackoff,
        maxBackoff = maxBackoff,
        randomFactor = randomFactor,
        maxRestarts = maxRestarts
      )(() => {
        logger.info(s"Starting source on Postgres channel '$postgresChannel'")
        PostgresPubSub.postgresListen(insecureContainer, postgresChannel)
      })
      .map(msg => ByteString(msg))
      .via(CirceStreamSupport.decode[NotificationMessage])
      .mapConcat(createIndividualMessages(_))
      .toMat(BroadcastHub.sink[NotificationMessage])(Keep.right)
      .run()

  val pingSource: Source[TextMessage.Strict, Cancellable] = {
    val users: List[Int] = List(0)
    val ping =
      new Ping(users, PingT, "PING", Instant.now().getEpochSecond).asJson.noSpaces
    val pingMessage = TextMessage.Strict(ping)
    Source.tick(pingInterval.seconds, pingInterval.seconds, pingMessage)
  }

  val serializeSeq: Flow[Seq[NotificationMessage], Message, NotUsed] =
    Flow[Seq[NotificationMessage]]
      .via(CirceStreamSupport.encode[Seq[NotificationMessage]])
      .map(TextMessage.Strict)

  def createIndividualMessages(
    message: NotificationMessage
  ): List[NotificationMessage] = {
    message match {

      case j: JobDoneNotification =>
        j.users.map { user: Int =>
          j.copy(users = List(user))
        }
      case a: AlertNotification =>
        a.users.map { user: Int =>
          a.copy(users = List(user))
        }
      case d: DatasetImportNotification =>
        d.users.map { user: Int =>
          d.copy(users = List(user))
        }
      case d: DatasetPublishNotification =>
        d.users.map { user: Int =>
          d.copy(users = List(user))
        }
      case d: DiscoverPublishNotification =>
        d.users.map { user: Int =>
          d.copy(users = List(user))
        }
      case m: MentionNotification =>
        m.users.map { user: Int =>
          m.copy(users = List(user))
        }
      case d: DatasetUpdateNotification =>
        d.users.map { user: Int =>
          d.copy(users = List(user))
        }
      case _ => List() //ignore pings, pongs and keepalives
      //note: attempting to handle this generically doesn't seem to work because the copy method isn't polymorphic
      // ? is there a better way (there's already base class NotificationMessage)
    }
  }

  def userShouldReceiveToast(message: NotificationMessage): Boolean = {
    message.users.nonEmpty && messageTriggersToast(message)
  }

  def messageTriggersToast(message: NotificationMessage): Boolean = {
    messageTypesToToast.contains(message.messageType)
  }

  def messageIsForUser(message: NotificationMessage, user: User): Boolean =
    message.users.contains(user.id) && !isSystemMessage(message)

  def isSystemMessage(message: NotificationMessage): Boolean = {
    systemMessageTypes.contains(message.messageType)
  }

  val parseWebSocketMessages: Flow[Message, NotificationMessage, NotUsed] =
    Flow[Message]
      .collect {
        case msg: TextMessage => msg // ignore binary messages
      }
      .mapAsyncUnordered(1)(_.toStrict(5.seconds))
      .map(strict => ByteString(strict.text))
      .via(CirceStreamSupport.decode[NotificationMessage])

  /**
    * When a PONG arrives keep the connection alive for another 30 seconds.
    *
    * Close the connection if the users session token is invalid.
    *
    * On the other end, read messages published through Postgres pub/sub. If
    * they are for this user, send them over the socket.
    */
  def webSocketNotificationFlow(
    authContext: UserAuthContext,
    insecureContainer: DIContainer
  ): Flow[Message, Message, NotUsed] = {
    parseWebSocketMessages
      .via(SessionMonitor(authContext))
      .via(PongMonitor(freshnessThreshold.seconds, authContext))
      // All other incoming messages are ignored. On the other side of the
      // coupling, the flow picks up messages from Postgres pub/sub source.
      .via(
        Flow
          .fromSinkAndSourceCoupled(Sink.ignore, notificationSource)
      )
      .filter(messageIsForUser(_, authContext.user))
      .groupedWithin(aggregationCount, aggregationInterval.seconds)
      .via(serializeSeq)
      .merge(pingSource, eagerComplete = true)
  }
}

class NotificationService(
  insecureContainer: DIContainer,
  config: Config
)(implicit
  system: ActorSystem,
  executionContext: ExecutionContext
) extends Directives
    with LazyLogging
    with RouteService {

  val notificationStream = new NotificationStream(insecureContainer)

  val jwtConfig = new Jwt.Config {
    override val key = config.as[String]("pennsieve.jwt.key")
  }

  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json()

  implicit val encodeNotUsed = deriveEncoder[NotUsed]
  implicit val decodeNotUsed = deriveDecoder[NotUsed]

  val realm = "notifications"

  def getNotificationsPerUser(userId: Int): List[NotificationMessage] = {
    val userNotifications: Future[List[NotificationMessage]] =
      insecureContainer.db.run(notifications.getNotificationsByUser(userId))
    Await.result(userNotifications, Duration.Inf)
  }

  def saveNotifications(
    notificationList: List[NotificationMessage],
    deliveryMethod: String
  ): EitherT[Future, domain.Error, Option[Int]] = {
    val rows = notificationList.flatMap {
      case jd: JobDoneNotification =>
        for (user <- jd.users)
          yield Notification(JobDone, user, deliveryMethod, jd)

      case a: AlertNotification =>
        for (user <- a.users)
          yield new Notification(Alert, user, deliveryMethod, a)

      case m: MentionNotification =>
        for (user <- m.users)
          yield new Notification(Mention, user, deliveryMethod, m)

      case _: DatasetUpdateNotification => List()

      case _ => List() //ignore pings, pongs and keepalives
    }

    insecureContainer.db
      .run(notifications ++= rows)
      .toEitherT
      .leftMap(_ => Error("problem inserting new notifications"))
  }

  def sendNotifications(message: NotificationMessage): Route = {

    val sentNotifications: EitherT[Future, Error, NotUsed] = Try {
      Source
        .single(message)
        .runWith(notificationStream.notificationSink)
    }.toEither.leftMap(e => Error(e.getMessage)).toEitherT[Future]

    val inAppNotifications: List[NotificationMessage] =
      notificationStream
        .createIndividualMessages(message)
        .filter(
          singleMessage =>
            notificationStream.userShouldReceiveToast(singleMessage)
        )

    val sentMsgs: EitherT[Future, Error, NotUsed] = for {
      sent <- sentNotifications
      _ <- saveNotifications(inAppNotifications, "In-app")
    } yield sent

    onComplete(sentMsgs.value) { f =>
      getResult(f) match {
        case Left(e) =>
          complete(StatusCodes.InternalServerError -> e.getMessage)
        case Right(msgs) => complete(msgs)
      }
    }
  }

  // *** Routes ***

  def userRoute: Route =
    internalJwtUser(insecureContainer, realm = realm)(
      jwtConfig,
      system.dispatcher
    ) { authContext =>
      path("connect") {
        handleWebSocketMessages(
          notificationStream
            .webSocketNotificationFlow(authContext, insecureContainer)
        )
      }
    } ~
      path("sendMention") {
        post {
          entity(as[NotificationMessage]) { m =>
            sendNotifications(m)
          }
        }
      }

  def adminRoute: Route =
    admin(insecureContainer, realm = realm)(
      jwtConfig,
      insecureContainer.cognitoConfig,
      system.dispatcher
    ) { _ =>
      path("send") {
        post {
          entity(as[NotificationMessage]) { n =>
            sendNotifications(n)
          }
        }
      } ~
        path("user") {
          get {
            parameter('userId) { userId =>
              complete(getNotificationsPerUser(userId.toInt))
            }
          }
        }
    }

  override def routes: Route = adminRoute ~ userRoute
}
