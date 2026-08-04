# ---------------------------------------------------------------------------
# Adopción de la infraestructura existente
#
# Estos bloques le dicen a Terraform que los recursos declarados en este módulo
# YA EXISTEN en AWS y que debe adoptarlos en lugar de crearlos. Nada aquí crea ni
# destruye: `terraform plan` debe salir SIN CAMBIOS.
#
# Si el plan propone crear algo, significa que falta un bloque de import.
# Si propone modificar algo, la declaración no coincide con la realidad y hay que
# corregir el .tf, NO aplicar el cambio.
#
# Una vez que el plan salga limpio y se haya hecho `terraform apply` (que solo
# escribirá el state, sin tocar nada), este archivo puede borrarse: los imports
# ya no hacen falta.
# ---------------------------------------------------------------------------

# --- Red -------------------------------------------------------------------

import {
  to = aws_security_group.alb
  id = "sg-0f48e25801a170bef"
}

import {
  to = aws_security_group.ecs_tasks
  id = "sg-09f1e2be72d76adbe"
}

import {
  to = aws_default_security_group.default
  id = "sg-050bc646783252d10"
}

import {
  to = aws_vpc_security_group_ingress_rule.alb_http_from_cloudfront
  id = "sgr-015f535b404015448"
}

import {
  to = aws_vpc_security_group_egress_rule.alb_all
  id = "sgr-09a4377ea3d7d1b9c"
}

import {
  to = aws_vpc_security_group_ingress_rule.ecs_from_alb
  id = "sgr-0d45e060a16ba174e"
}

import {
  to = aws_vpc_security_group_egress_rule.ecs_all
  id = "sgr-0734c6db6a9f8cb52"
}

# --- Balanceador -----------------------------------------------------------

import {
  to = aws_lb.main
  id = "arn:aws:elasticloadbalancing:mx-central-1:274869222183:loadbalancer/app/bible-alb/6372b1995d9da0cf"
}

import {
  to = aws_lb_target_group.app
  id = "arn:aws:elasticloadbalancing:mx-central-1:274869222183:targetgroup/bible-tg/29e3a2cf8e88e754"
}

import {
  to = aws_lb_listener.http
  id = "arn:aws:elasticloadbalancing:mx-central-1:274869222183:listener/app/bible-alb/6372b1995d9da0cf/7125b07b7a6f6016"
}

import {
  to = aws_lb_listener_rule.cloudfront_origin_verify
  id = "arn:aws:elasticloadbalancing:mx-central-1:274869222183:listener-rule/app/bible-alb/6372b1995d9da0cf/7125b07b7a6f6016/3d7e1478c5908164"
}

# --- Cómputo ---------------------------------------------------------------

import {
  to = aws_ecs_cluster.main
  # Por nombre, no por ARN: el proveedor construye el ARN por su cuenta y
  # pasarle uno completo produce un ARN duplicado.
  id = "biblia-cluster"
}

import {
  to = aws_ecs_cluster_capacity_providers.main
  id = "biblia-cluster"
}

import {
  to = aws_ecs_task_definition.app
  id = "arn:aws:ecs:mx-central-1:274869222183:task-definition/bible-references-task:21"
}

import {
  to = aws_ecs_service.app
  id = "biblia-cluster/bible-references-service"
}

import {
  to = aws_ecr_repository.app
  id = "bible-references"
}

import {
  to = aws_ecr_lifecycle_policy.app
  id = "bible-references"
}

# --- IAM -------------------------------------------------------------------

import {
  to = aws_iam_role.ecs_task_execution
  id = "ecsTaskExecutionRole"
}

import {
  to = aws_iam_role_policy_attachment.ecs_task_execution
  id = "ecsTaskExecutionRole/arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

import {
  to = aws_iam_role_policy_attachment.secrets_manager_read_write
  id = "ecsTaskExecutionRole/arn:aws:iam::aws:policy/SecretsManagerReadWrite"
}

# --- Datos, secretos y logs ------------------------------------------------

import {
  to = aws_db_instance.main
  id = "database-2"
}

import {
  to = aws_secretsmanager_secret.db
  id = "arn:aws:secretsmanager:mx-central-1:274869222183:secret:bible-references/db-UcCWiR"
}

import {
  to = aws_cloudwatch_log_group.ecs
  id = "/ecs/bible-references"
}

# --- Frontend --------------------------------------------------------------

import {
  to = aws_s3_bucket.frontend
  id = "biblia-frontend-prod"
}

import {
  to = aws_s3_bucket_public_access_block.frontend
  id = "biblia-frontend-prod"
}

import {
  to = aws_s3_bucket_policy.frontend
  id = "biblia-frontend-prod"
}

import {
  to = aws_cloudfront_origin_access_control.frontend
  id = "E3CY5FU0240O7J"
}

import {
  to = aws_cloudfront_distribution.main
  id = "E1MTT1XP2UUMYU"
}

# ---------------------------------------------------------------------------
# Deliberadamente FUERA del módulo
#
#   Certificado ACM (us-east-1)   no hay hosted zone en Route53; el DNS es
#                                 externo, así que Terraform no puede crear los
#                                 registros de validación
#   WAF WebACL                    creada desde la consola de CloudFront; se
#                                 referencia por ARN para no perder la asociación
#   VPC, subredes, tabla de rutas recursos de la cuenta, no del proyecto
#                                 (biblia-sg y biblia-alb-sg ya no aparecen aquí:
#                                 se eliminaron el 2026-08-04, hallazgo 7)
#   Versión del secreto de BD     contiene la contraseña; no debe entrar al state
# ---------------------------------------------------------------------------
