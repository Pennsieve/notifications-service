resource "aws_ssm_parameter" "aggregation_count" {
  name  = "/${var.environment_name}/${var.service_name}/aggregation-count"
  type  = "String"
  value = var.aggregation_interval
}

resource "aws_ssm_parameter" "aggregation_interval" {
  name  = "/${var.environment_name}/${var.service_name}/aggregation-interval"
  type  = "String"
  value = var.aggregation_interval
}

resource "aws_ssm_parameter" "pennsieve_postgres_database" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-database"
  type  = "String"
  value = var.pennsieve_postgres_database
}

resource "aws_ssm_parameter" "pennsieve_postgres_host" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-host"
  type  = "String"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_fqdn
}

# The notifications user password was manually added and then imported into TF state
resource "aws_ssm_parameter" "pennsieve_postgres_password" {
  name      = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-password"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "pennsieve_postgres_port" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-port"
  type  = "String"
  value = data.terraform_remote_state.pennsieve_postgres.outputs.master_port
}

resource "aws_ssm_parameter" "pennsieve_postgres_user" {
  name  = "/${var.environment_name}/${var.service_name}/pennsieve-postgres-user"
  type  = "String"
  value = "${var.environment_name}_${replace(var.service_name, "-", "_")}_user"
}

resource "aws_ssm_parameter" "freshness_threshold" {
  name  = "/${var.environment_name}/${var.service_name}/freshness-threshold"
  type  = "String"
  value = var.freshness_threshold
}

resource "aws_ssm_parameter" "jwt_secret_key" {
  name      = "/${var.environment_name}/${var.service_name}/jwt-secret-key"
  overwrite = false
  type      = "SecureString"
  value     = "dummy"

  lifecycle {
    ignore_changes = [value]
  }
}

resource "aws_ssm_parameter" "ping_interval" {
  name  = "/${var.environment_name}/${var.service_name}/ping-interval"
  type  = "String"
  value = var.ping_interval
}

resource "aws_ssm_parameter" "notifications_sqs_queue" {
  name  = "/${var.environment_name}/${var.service_name}/notifications-sqs-queue"
  type  = "String"
  value = data.terraform_remote_state.platform_infrastructure.outputs.notifications_queue_id
}

resource "aws_ssm_parameter" "cognito_user_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-user-pool-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.user_pool_id
}

resource "aws_ssm_parameter" "cognito_user_pool_app_client_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-user-pool-app-client-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.user_pool_client_id
}

resource "aws_ssm_parameter" "cognito_token_pool_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-token-pool-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.token_pool_id
}

resource "aws_ssm_parameter" "cognito_token_pool_app_client_id" {
  name  = "/${var.environment_name}/${var.service_name}/cognito-token-pool-app-client-id"
  type  = "String"
  value = data.terraform_remote_state.authentication_service.outputs.token_pool_client_id
}
