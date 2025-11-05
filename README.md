## Retired: This service has been retired and its AWS resources deleted.

## Notifications

The notifications api sends messages to individual users via websocket connections. Messages can be sent via an SQS queue or the REST API.

![Notifications Diagram](https://user-images.githubusercontent.com/147873/47174467-a190e380-d2de-11e8-985e-27fed9f950dc.png)

- the NotificationsSink is a MergeHub, meaning that it can accept new sources (from api requests) at any time.
- the NotificationsSource is a BroadcastHub, meaning that it can accept new websocket connections from new users at anytime.
- all messages go to every user connection, but each source is filtered so that only the messages addressed to a particular user will make it through.
