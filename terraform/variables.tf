variable "aws_account" {}

variable "environment_name" {}

variable "service_name" {}

variable "vpc_name" {}

variable "ecs_task_iam_role_id" {}

# Notifications Service Parameters

variable "aggregation_count" {
  default = "10"
}

variable "aggregation_interval" {
  default = "15"
}

variable "external_alb_listener_rule_priority" {
  default = 7
}

variable "freshness_threshold" {
  default = "30"
}

variable "keepalive_interval" {
  default = "300"
}

variable "ping_interval" {
  default = "10"
}

variable "pennsieve_postgres_database" {
  default = "pennsieve_postgres"
}
