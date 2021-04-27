package com.pennsieve.notifications.api

import com.pennsieve.dtos.{ PackageDTO, WrappedPackage }
import com.pennsieve.models.{ FileType, PackageState, PackageType, PayloadType }
import com.pennsieve.models.CognitoId.UserPoolId
import com.pennsieve.core.utilities.UserAuthContext
import com.pennsieve.aws.cognito.CognitoPayload
import java.time.Instant
import scala.concurrent.duration._
import com.pennsieve.notifications.MessageType.{ JobDone, PingT }
import com.pennsieve.notifications._
import com.pennsieve.notifications.{
  ETLNotification,
  JobDoneNotification,
  NotificationMessage,
  Pong
}
import akka.actor.ActorSystem
import akka.NotUsed
import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.mdedetrich.akka.stream.support.CirceStreamSupport
import io.circe.java8._
import io.circe.java8.time._
import io.circe.syntax._
import java.time.ZonedDateTime

import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.duration._
import scala.collection.{ immutable, mutable }

class TestNotificationFlows
    extends WordSpec
    with NotificationsDatabaseBaseSpec
    with Matchers {

  val wrappedPkg = WrappedPackage(
    id = "packageNodeId",
    nodeId = "packageNodeId",
    name = "Test Package",
    packageType = PackageType.TimeSeries,
    datasetId = "datasetNodeId",
    datasetNodeId = "datasetNodeId",
    ownerId = None,
    state = PackageState.READY,
    parentId = None,
    createdAt = ZonedDateTime.now(),
    updatedAt = ZonedDateTime.now,
    intId = 10,
    datasetIntId = 2
  )

  val testPackageDTO =
    PackageDTO(
      content = wrappedPkg,
      properties = List.empty,
      parent = None,
      objects = None,
      children = List.empty,
      ancestors = None,
      channels = None,
      externalFile = None,
      storage = None
    )

  val serializeFlowTest: Flow[NotificationMessage, Message, NotUsed] =
    Flow[NotificationMessage]
      .via(CirceStreamSupport.encode[NotificationMessage])
      .map(TextMessage.Strict(_))

  val parseFlowTest: Flow[Message, Seq[NotificationMessage], NotUsed] =
    Flow[Message]
      .map {
        case TextMessage.Strict(s) => ByteString(s)
      }
      .via(CirceStreamSupport.decode[Seq[NotificationMessage]])

  "sending messages through the notification flow" should {
    "group and filter by user" in {
      val user = createUser()
      val notificationStream = new NotificationStream(notificationsContainer)

      val msgs = 1 to 30 map { i =>
        JobDoneNotification(
          if (i % 2 == 0) List(200) else List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          s"done $i"
        )
      }

      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      //expect only odd numbered msgs, since those are the ones that contain user.id

      val expected = Vector(
        JobDoneNotification(
          List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          "done 1"
        ),
        JobDoneNotification(
          List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          "done 3"
        ),
        JobDoneNotification(
          List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          "done 5"
        ),
        JobDoneNotification(
          List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          "done 7"
        ),
        JobDoneNotification(
          List(user.id),
          JobDone,
          "mydataset",
          testPackageDTO,
          "done 9"
        )
      )

      val sinkProbe = Source
        .repeat(Pong(List(user.id), PingT, "PING", "12345"))
        .throttle(1, 1.second)
        .via(serializeFlowTest)
        .via(
          notificationStream
            .webSocketNotificationFlow(authContext, notificationsContainer)
        )
        .via(parseFlowTest)
        .runWith(TestSink.probe[Seq[NotificationMessage]])

      // wait for pub-sub to start
      Thread.sleep(1000)

      Source(msgs)
        .runWith(notificationStream.notificationSink)

      sinkProbe
        .request(1)
        .expectNext(expected)
    }
  }

  "parseFlow" should {
    "decode ETL notifications" in {
      val notificationStream = new NotificationStream(notificationsContainer)

      val etlNotification: NotificationMessage = ETLNotification(
        List(10),
        JobDone,
        true,
        PayloadType.Upload,
        "abc123",
        5,
        12345,
        123,
        List("test@blackfynn.com/abc123/test.bfts"),
        FileType.BFTS,
        PackageType.TimeSeries,
        "Upload job complete"
      )

      val msg = TextMessage.Strict(etlNotification.asJson.noSpaces)

      Source
        .single(msg)
        .via(notificationStream.parseWebSocketMessages)
        .runWith(TestSink.probe[NotificationMessage])
        .request(1)
        .expectNext(etlNotification)
    }
  }

  "createIndividualMessages" should {
    "create one notification per user" in {

      val notificationStream = new NotificationStream(notificationsContainer)

      val message1 = new JobDoneNotification(
        List(1, 2, 3),
        JobDone,
        "Test Dataset",
        testPackageDTO,
        "This is a test."
      )

      val expectedResult = List(
        JobDoneNotification(
          List(1),
          JobDone,
          "Test Dataset",
          testPackageDTO,
          "This is a test."
        ),
        JobDoneNotification(
          List(2),
          JobDone,
          "Test Dataset",
          testPackageDTO,
          "This is a test."
        ),
        JobDoneNotification(
          List(3),
          JobDone,
          "Test Dataset",
          testPackageDTO,
          "This is a test."
        )
      )

      val result = notificationStream.createIndividualMessages(message1)

      assert(result == expectedResult)
    }
  }

  "Pong timeout Flow" should {

    "consume pong messages without timing out" in {
      val sessionId = "12345"
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val (sourceProbe, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(PongMonitor(1.second, authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      sourceProbe.sendNext(
        Pong(users = List(0), message = "PONG", sessionId = sessionId)
      )
      sourceProbe.sendComplete()
      sinkProbe.expectSubscriptionAndComplete()
    }

    "timeout and error when it does not see a pong" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val (_, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(PongMonitor(1.second, authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      Thread.sleep(2000)

      sinkProbe.expectSubscriptionAndError() shouldBe an[TimeoutException]
    }

  }

  "Session validator flow" should {

    "stream normally while session is valid" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(60))
        )
      )

      val messages = List(1 to 5) map (
        i => Pong(users = List(0), message = s"PONG $i", sessionId = "54321")
      )

      val sinkProbe = Source(messages)
        .throttle(1, 1.second)
        .via(SessionMonitor(authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.right)
        .run()

      sinkProbe.request(n = 5)
      messages.map(sinkProbe.expectNext(_))
      sinkProbe.expectComplete()
    }

    "cancel stream with error when the session is no longer valid" in {
      val user = createUser()
      val authContext = new UserAuthContext(
        user = user,
        organization = organization,
        cognitoPayload = Some(
          CognitoPayload(UserPoolId.randomId(), Instant.now().plusSeconds(1))
        )
      )

      val (sourceProbe, sinkProbe) = TestSource
        .probe[NotificationMessage]
        .via(SessionMonitor(authContext))
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

      val msg = Pong(users = List(0), message = "PONG", sessionId = "54321")

      sourceProbe.sendNext(msg)
      sinkProbe.request(n = 1)
      sinkProbe.expectNext(msg)

      Thread.sleep(2000)

      sourceProbe.sendNext(msg)
      sinkProbe.request(n = 1)
      sinkProbe.expectError() shouldBe SessionExpired
    }
  }

  "pub sub" should {

    "broadcast notifications through Postgres pub sub" in {
      val notificationStream1 = new NotificationStream(notificationsContainer)
      val notificationStream2 = new NotificationStream(notificationsContainer)
      val notificationStream3 = new NotificationStream(notificationsContainer)

      val notification = DatasetUpdateNotification(
        users = List(1),
        datasetId = 2,
        datasetName = "Test dataset",
        message = "Status changed"
      )

      val receivedOnStream1 = notificationStream1.notificationSource
        .toMat(Sink.head)(Keep.right)
        .run()

      val receivedOnStream2 = notificationStream2.notificationSource
        .toMat(Sink.head)(Keep.right)
        .run()

      // wait for listen channels to start
      Thread.sleep(1000)

      Source
        .single(notification)
        .runWith(notificationStream3.notificationSink)

      receivedOnStream1.awaitFinite() shouldBe notification
      receivedOnStream2.awaitFinite() shouldBe notification
    }
  }
}
