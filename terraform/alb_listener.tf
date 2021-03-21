// CREATE ALB LISTENER RULE
resource "aws_alb_listener_rule" "alb_listener_rule" {
  listener_arn = data.terraform_remote_state.ecs_cluster.outputs.external_alb_listener_arn
  priority     = var.external_alb_listener_rule_priority

  action {
    type = "redirect"

    redirect {
      protocol    = "HTTPS"
      host        = data.terraform_remote_state.gateway.outputs.external_fqdn
      path        = "/notifications/#{path}"
      status_code = "HTTP_301"
    }
  }

  condition {
    host_header {
      values = ["${element(split("-", var.service_name), 0)}*.${data.terraform_remote_state.account.outputs.domain_name}"]
    }
  }

  lifecycle {
    create_before_destroy = true
  }
}
