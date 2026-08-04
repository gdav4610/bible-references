resource "aws_ecs_cluster" "main" {
  name = "biblia-cluster"

  configuration {
    execute_command_configuration {
      logging = "DEFAULT"
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE"]
}

# ---------------------------------------------------------------------------
# Task definition
#
# Revisión 21: health check restaurado tras confirmar que la imagen incluye curl.
# El histórico está en docs/runbook-hallazgo-0-bucle-reinicio.md.
#
# `startPeriod` de 180 s frente a un arranque medido de ~64 s. No bajar de ahí
# sin volver a medir: la app levanta Hibernate, 6 repositorios JPA y Hazelcast
# embebido en medio vCPU.
#
# OJO con el pipeline: .github/workflows/aws-dep.yml hace
# `aws ecs describe-task-definition > task-definition.json` y despliega eso, así
# que cada push registra una revisión nueva por su cuenta. Mientras siga así,
# Terraform y el pipeline compiten por este recurso — ver el README.
# ---------------------------------------------------------------------------

resource "aws_ecs_task_definition" "app" {
  family                   = "bible-references-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"

  # Ambos apuntan al mismo rol: hallazgo 5, sin resolver.
  execution_role_arn = aws_iam_role.ecs_task_execution.arn
  task_role_arn      = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([
    {
      name      = "bible-references"
      image     = var.container_image
      cpu       = 0
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SPRING_JPA_SHOW_SQL", value = "false" },
        { name = "SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT", value = "org.hibernate.dialect.PostgreSQLDialect" },
      ]

      secrets = [
        { name = "SPRING_DATASOURCE_URL", valueFrom = "${aws_secretsmanager_secret.db.arn}:url::" },
        { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
        { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.ecs.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 15
        retries     = 3
        startPeriod = 180
      }

      mountPoints    = []
      volumesFrom    = []
      systemControls = []
    }
  ])

  lifecycle {
    # El pipeline sobrescribe la imagen en cada despliegue. Sin esto, Terraform
    # querría revertirla a var.container_image en cada plan.
    ignore_changes = [container_definitions]
  }
}

# ---------------------------------------------------------------------------
# Servicio
#
# desired_count = 1 (hallazgo 3). Subirlo exige antes resolver el hallazgo 4:
# Hazelcast usa descubrimiento por multicast, que no funciona en una VPC, así
# que cada task tendría su propia caché aislada.
# ---------------------------------------------------------------------------

resource "aws_ecs_service" "app" {
  name             = "bible-references-service"
  cluster          = aws_ecs_cluster.main.id
  task_definition  = aws_ecs_task_definition.app.arn
  desired_count    = 1
  launch_type      = "FARGATE"
  platform_version = "LATEST"

  scheduling_strategy                = "REPLICA"
  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100
  health_check_grace_period_seconds  = 120
  enable_execute_command             = false
  propagate_tags                     = "NONE"

  deployment_circuit_breaker {
    enable   = false
    rollback = false
  }

  network_configuration {
    subnets          = local.ecs_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "bible-references"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.http]

  lifecycle {
    # Cada push a main despliega una revisión nueva desde GitHub Actions.
    ignore_changes = [task_definition]
  }
}
