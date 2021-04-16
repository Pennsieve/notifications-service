// Copyright (c) 2017 Pennsieve All Rights Reserved.

package com.pennsieve.notifications.api.db

import com.pennsieve.notifications.{ MessageType, NotificationMessage }
import com.pennsieve.traits.PostgresProfile.api._

import io.circe._
import io.circe.syntax._
import io.circe.java8._
import io.circe.java8.time._
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType

package object implicits {

  implicit val MessageTypeMapper = MappedColumnType
    .base[MessageType, String](m => m.entryName, s => MessageType.withName(s))

  implicit val MessageContentMapper
    : JdbcType[NotificationMessage] with BaseTypedType[NotificationMessage] =
    MappedColumnType.base[NotificationMessage, Json](
      (notification: NotificationMessage) => notification.asJson,
      (json: Json) => json.as[NotificationMessage].right.get
    )

}
