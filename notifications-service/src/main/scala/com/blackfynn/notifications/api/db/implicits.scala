// Copyright (c) 2017 Blackfynn, Inc. All Rights Reserved.

package com.blackfynn.notifications.api.db

import com.blackfynn.notifications.{ MessageType, NotificationMessage }
import com.blackfynn.traits.PostgresProfile.api._

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
