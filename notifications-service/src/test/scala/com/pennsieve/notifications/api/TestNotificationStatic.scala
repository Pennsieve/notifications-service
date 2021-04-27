// package com.pennsieve.notifications.api

// import akka.actor.ActorSystem
// import com.pennsieve.dtos.{ PackageDTO, WrappedPackage }
// import com.pennsieve.models.PackageState
// import com.pennsieve.notifications.MessageType.JobDone
// import com.pennsieve.notifications.{ JobDoneNotification, NotificationMessage }
// import com.pennsieve.notifications._
// import com.typesafe.config.{ Config, ConfigFactory }
// import java.time.ZonedDateTime

// import com.pennsieve.models.PackageType
// import org.scalatest.FlatSpec

// class TestNotificationStatic extends FlatSpec {
//   implicit val system = ActorSystem("test-system")
//   implicit val executionContext = system.dispatcher

//   val config: Config = ConfigFactory.load()
//   val notificationStream = new NotificationStream(notificationsContainer)

//   val wrappedPkg = WrappedPackage(
//     id = "packageNodeId",
//     nodeId = "packageNodeId",
//     name = "Test Package",
//     packageType = PackageType.TimeSeries,
//     datasetId = "datasetNodeId",
//     datasetNodeId = "datasetNodeId",
//     ownerId = None,
//     state = PackageState.READY,
//     parentId = None,
//     createdAt = ZonedDateTime.now(),
//     updatedAt = ZonedDateTime.now,
//     intId = 10,
//     datasetIntId = 2
//   )

//   val testPackageDTO =
//     PackageDTO(
//       content = wrappedPkg,
//       properties = List.empty,
//       parent = None,
//       objects = None,
//       children = List.empty,
//       ancestors = None,
//       channels = None,
//       externalFile = None,
//       storage = None
//     )
// }
