// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.notifications.api.db

import java.time.ZonedDateTime

import com.blackfynn.traits.PostgresProfile.api._
import com.blackfynn.notifications.api.db.implicits._
import com.blackfynn.notifications.{
  MessageType,
  Notification,
  NotificationMessage
}

final class NotificationTable(tag: Tag)
    extends Table[Notification](tag, Some("pennsieve"), "notifications") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def messageType = column[MessageType]("message_type")
  def userId = column[Int]("user_id")
  def deliveryMethod = column[String]("delivery_method")
  def messageContent = column[NotificationMessage]("message_content")
  def createdAt =
    column[ZonedDateTime]("created_at", O.AutoInc) // set by the database on insert

  def * =
    (messageType, userId, deliveryMethod, messageContent, createdAt, id)
      .mapTo[Notification]
}

object notifications extends TableQuery(new NotificationTable(_)) {
  def getId(id: Int) = this.filter(_.id === id).map(_.id).result.head
  def getNotificationsByUser(userId: Int) =
    this.filter(_.userId === userId).to[List].map(_.messageContent).result
}
