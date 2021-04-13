package com.pennsieve.notifications.api

import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import com.pennsieve.models.{ FileType, PackageType, PayloadType }
import com.pennsieve.notifications.MessageType.JobDone
import com.pennsieve.notifications._
import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.EitherValues._
import org.scalatest.FlatSpec
import software.amazon.awssdk.services.sqs.model.{
  GetQueueAttributesRequest,
  QueueAttributeName
}

import scala.compat.java8.FutureConverters._
import scala.collection.JavaConverters._

class TestSQSSource extends FlatSpec with NotificationsDatabaseBaseSpec {

  "SQSSource" should "process ETL notifications" in {
    // Create SQS queues
    val queue = notificationsContainer.sqs
      .createQueue("notifications")
      .await
      .queueUrl

    // Create assets
    val user = createUser()
    val dataset = createDataset
    val importId = java.util.UUID.randomUUID()
    val pkg =
      createPackage(dataset = dataset, ownerId = user.id, importId = importId)
    val exportPackage = createPackage(dataset = dataset, ownerId = user.id)

    // Set up SQS graph
    val sqsGraph: SQSSource = new SQSSource(config, notificationsContainer)
    val (killswitch, probe) =
      sqsGraph.wsMessageSource
        .toMat(TestSink.probe[NotificationMessage])(Keep.both)
        .run()

    val users = List(user.id)
    val importIdString = importId.toString
    val organizationId = organization.id
    val packageId = pkg.id
    val datasetId = dataset.id
    val files = List("test@blackfynn.com/abc123/test.bfts")
    // Send an ETLNotification to the queues

    val uploadNotification =
      UploadNotification(
        users,
        success = true,
        datasetId,
        packageId,
        organizationId,
        files
      )
    val etlNotification =
      ETLNotification(
        users,
        JobDone,
        success = true,
        PayloadType.Append,
        importIdString,
        organizationId,
        packageId,
        datasetId,
        files,
        FileType.BFTS,
        PackageType.TimeSeries,
        "Append job complete"
      )
    val workflowNotification =
      ETLNotification(
        users,
        JobDone,
        success = true,
        PayloadType.Workflow,
        importIdString,
        organizationId,
        packageId,
        datasetId,
        files,
        FileType.PNG,
        PackageType.Image,
        "Workflow job complete"
      )
    val exportNotification =
      ETLExportNotification(
        users,
        JobDone,
        success = true,
        PayloadType.Workflow,
        importIdString,
        organizationId,
        exportPackage.id,
        datasetId,
        FileType.NeuroDataWithoutBorders,
        PackageType.HDF5,
        packageId,
        pkg.`type`,
        "Export complete"
      )

    List[NotificationMessage](
      etlNotification,
      uploadNotification,
      workflowNotification,
      exportNotification
    ).foreach { message =>
      notificationsContainer.sqs
        .send(queue, message.asJson.noSpaces)
        .await
        .right
        .get

      val sentMessage = probe.requestNext().asInstanceOf[JobDoneNotification]

      sentMessage.users shouldEqual users
      sentMessage.datasetName shouldEqual dataset.name
      sentMessage.packageDTO.content.id shouldEqual pkg.nodeId
      sentMessage.packageDTO.content.name shouldEqual pkg.name
    }

    // Confirm that the graph is acknowledging/deleting SQS messages
    numberOfMessagesInQueue(queue) shouldEqual 0

    killswitch.shutdown()
    probe.cancel()
  }

  private def numberOfMessagesInQueue(queueName: String): Int =
    notificationsContainer.sqs.client
      .getQueueAttributes(
        GetQueueAttributesRequest
          .builder()
          .queueUrl(queueName)
          .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
          .build()
      )
      .toScala
      .await
      .attributes()
      .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
      .toInt
}
