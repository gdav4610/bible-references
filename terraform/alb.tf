# ---------------------------------------------------------------------------
# Balanceador
#
# Solo tiene listener HTTP:80 y es correcto: CloudFront termina TLS en el edge y
# habla con el origen en http-only. Cifrar ese tramo exigiría un listener 443 con
# certificado ACM regional y cambiar el origen a https-only; está pendiente.
# ---------------------------------------------------------------------------

resource "aws_lb" "main" {
  name               = "bible-alb"
  internal           = false
  load_balancer_type = "application"
  ip_address_type    = "ipv4"
  security_groups    = [aws_security_group.alb.id]
  subnets            = local.alb_subnet_ids

  idle_timeout                     = 60
  enable_http2                     = true
  enable_cross_zone_load_balancing = true
  enable_deletion_protection       = false
  desync_mitigation_mode           = "defensive"
  drop_invalid_header_fields       = false
  preserve_host_header             = false

}

resource "aws_lb_target_group" "app" {
  name        = "bible-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = data.aws_vpc.main.id

  deregistration_delay               = 300
  slow_start                         = 0
  lambda_multi_value_headers_enabled = false
  proxy_protocol_v2                  = false

  health_check {
    enabled             = true
    path                = "/actuator/health"
    port                = "traffic-port"
    protocol            = "HTTP"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  stickiness {
    enabled = false
    type    = "lb_cookie"
  }

}

# ---------------------------------------------------------------------------
# Listener — HALLAZGO 2, FASE 2
#
# La acción por defecto rechaza con 403. Solo la regla de prioridad 1, que exige
# el header secreto que inyecta CloudFront, reenvía al target group.
#
# El orden importa si algún día se recrea esto desde cero: primero la regla,
# después el default. Al revés se corta producción entre un paso y el siguiente.
# ---------------------------------------------------------------------------

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "cloudfront_origin_verify" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 1

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [var.origin_verify_secret]
    }
  }
}
