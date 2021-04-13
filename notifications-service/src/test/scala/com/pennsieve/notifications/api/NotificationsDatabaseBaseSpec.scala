package com.pennsieve.notifications.api

import java.util.UUID

import cats.implicits._
import com.pennsieve.db.{
  DatasetStatusMapper,
  DatasetsMapper,
  FilesMapper,
  PackagesMapper,
  UserMapper
}
import com.pennsieve.models.{
  DBPermission,
  Dataset,
  DatasetStatus,
  NodeCodes,
  Package,
  PackageState,
  PackageType,
  User
}
import com.pennsieve.traits.PostgresProfile.api._
import org.scalatest.{ Matchers, Suite }

import scala.concurrent.Future
import scala.util.Random

trait NotificationsDatabaseBaseSpec
    extends NotificationsBaseSpec
    with Matchers { self: Suite =>

  val packages = new PackagesMapper(organization)
  val datasets = new DatasetsMapper(organization)
  val files = new FilesMapper(organization)
  val datasetStatuses = new DatasetStatusMapper(organization)

  def createDataset: Dataset = {
    val status = DatasetStatus(
      name = "MY_STATUS",
      displayName = "My Status",
      color = "#08B3AF",
      originalName = None
    )

    notificationsContainer.db
      .run((datasetStatuses returning datasetStatuses) += status)
      .await

    val dataset = Dataset(
      nodeId = NodeCodes.generateId(NodeCodes.dataSetCode),
      name = "Test Dataset",
      statusId = 1
    )

    notificationsContainer.db
      .run((datasets returning datasets) += dataset)
      .await
  }

  def createPackage(
    dataset: Dataset,
    state: PackageState = PackageState.UNAVAILABLE,
    `type`: PackageType = PackageType.TimeSeries,
    ownerId: Int,
    importId: UUID = UUID.randomUUID
  ): Package = {

    val p = Package(
      nodeId = NodeCodes.generateId(NodeCodes.packageCode),
      name = "Test Package",
      `type` = `type`,
      datasetId = dataset.id,
      ownerId = Some(ownerId),
      state = state,
      importId = Some(importId)
    )

    notificationsContainer.db
      .run((packages returning packages) += p)
      .await
  }

  def createUser(
    email: String = "test+" + generateRandomString() + "@blackfynn.com",
    password: String = "password",
    isSuperAdmin: Boolean = false
  ): User = {
    val user = User(
      nodeId = NodeCodes.generateId(NodeCodes.userCode),
      email = email,
      firstName = "",
      lastName = "",
      middleInitial = None,
      degree = None,
      credential = "",
      color = "",
      url = "",
      isSuperAdmin = isSuperAdmin
    )

    val result = for {
      user <- notificationsContainer.db
        .run((UserMapper returning UserMapper) += user)

      _ <- notificationsContainer.organizationManager
        .addUser(organization, user, DBPermission.Write)
        .fold(Future.failed, Future.successful)
    } yield user

    result.await
  }

  def generateRandomString(size: Int = 10): String =
    Random.alphanumeric.filter(_.isLetter).take(size).mkString

}
