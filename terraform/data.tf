# Import Current Session Data
data "aws_caller_identity" "current" {}

data "aws_region" "current_region" {}

# Import Account Data
data "terraform_remote_state" "account" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Region Data
data "terraform_remote_state" "region" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import BF Postgres Data
data "terraform_remote_state" "pennsieve_postgres" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/pennsieve-postgres/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import ECS Cluster Data
data "terraform_remote_state" "ecs_cluster" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/ecs-cluster/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Gateway Data
data "terraform_remote_state" "gateway" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/gateway/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Platform Infrastructure Data
data "terraform_remote_state" "platform_infrastructure" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/platform-infrastructure/terraform.tfstate"
    region = "us-east-1"
  }
}

# Import Cognito Authentication Data
data "terraform_remote_state" "authentication_service" {
  backend = "s3"

  config = {
    bucket = "${var.aws_account}-terraform-state"
    key    = "aws/${data.aws_region.current_region.name}/${var.vpc_name}/${var.environment_name}/authentication-service/terraform.tfstate"
    region = "us-east-1"
  }
}
