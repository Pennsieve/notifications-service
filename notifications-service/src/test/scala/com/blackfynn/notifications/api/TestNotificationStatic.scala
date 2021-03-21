package com.blackfynn.notifications.api

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.blackfynn.dtos.{ PackageDTO, WrappedPackage }
import com.blackfynn.models.PackageState
import com.blackfynn.notifications.MessageType.JobDone
import com.blackfynn.notifications._
import com.typesafe.config.{ Config, ConfigFactory }
import java.time.ZonedDateTime

import com.blackfynn.models.PackageType
import org.scalatest.FlatSpec

class TestNotificationStatic extends FlatSpec {
  implicit val system = ActorSystem("test-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val config: Config = ConfigFactory.load()
  val notificationStream = new NotificationStream(config)

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

  "createIndividualMessages" should "create one notification per user" in {
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
