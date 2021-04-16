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
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestProbe
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.mdedetrich.akka.stream.support.CirceStreamSupport
import io.circe.java8._
import io.circe.java8.time._
import io.circe.syntax._
import java.time.ZonedDateTime

import org.scalatest.{ FlatSpec, Matchers }

import scala.concurrent.duration._
import scala.collection.{ immutable, mutable }

class TestNotificationFlows
    extends FlatSpec
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

  "sending messages through the notification flow" should "group and filter by user" in {
    val user = createUser()
    val notificationStream = new NotificationStream(config)

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
      .via(notificationStream.webSocketNotificationFlow(authContext))
      .via(parseFlowTest)
      .runWith(TestSink.probe[Seq[NotificationMessage]])

    Source(msgs)
      .runWith(notificationStream.notificationSink)

    sinkProbe
      .request(1)
      .expectNext(expected)
  }

  "parseFlow" should "decode ETL notifications" in {
    val notificationStream = new NotificationStream(config)

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
