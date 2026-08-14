resource "aws_ecs_cluster" "main" {
  name = "biblia-cluster"

  configuration {
    execute_command_configuration {
      logging = "DEFAULT"
    }
  }
}

# FARGATE_SPOT añadido el 2026-08-13 para bajar costes. Se dejan los dos
# declarados: el servicio corre en Spot, pero mantener FARGATE disponible
# permite volver a on-demand con un solo `update-service` si Spot resulta
# demasiado inestable.
resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
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

  # ARM64 — PREPARADO PERO NO APLICADO (2026-08-13)
  #
  # Fargate en Graviton cuesta ~20% menos que en x86. El `Dockerfile` y el
  # workflow YA construyen para linux/arm64, pero la revisión desplegada sigue
  # siendo x86, así que este bloque queda comentado para que el módulo siga
  # reflejando la realidad.
  #
  # Con Fargate Spot ya aplicado, el ahorro adicional de ARM64 baja a ~1,44
  # USD/mes: el 20% se calcula sobre una base ya descontada al 30%. Por eso dejó
  # de ser prioritario.
  #
  # Al activarlo, descomentar este bloque Y asegurarse de que la imagen arm64
  # está en ECR ANTES de registrar la revisión. Una imagen x86 sobre
  # runtimePlatform ARM64 —o al revés— no arranca (`exec format error`).
  # Procedimiento en docs/runbook-ahorro-costos.md, punto 3.
  #
  # runtime_platform {
  #   operating_system_family = "LINUX"
  #   cpu_architecture        = "ARM64"
  # }

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
#
# FARGATE_SPOT desde el 2026-08-13: hasta un 70% más barato que on-demand. El
# precio es que AWS puede reclamar la capacidad con 2 minutos de aviso; con una
# sola task y un arranque de ~64 s, cada interrupción son ~2 minutos de 503.
# Es una compensación aceptada a la vista del tráfico (pocos usuarios de día,
# ninguno de noche). Para volver a on-demand, cambiar el capacity provider a
# FARGATE y forzar un despliegue nuevo.
#
# desired_count queda a 1 pero lo pisa el apagado nocturno: EventBridge
# Scheduler lo baja a 0 a la 01:00 y lo sube a 1 a las 06:55, hora de Ciudad de
# México (ver scheduler.tf). De ahí el ignore_changes de abajo: sin él, cualquier
# `apply` que corriera de madrugada volvería a levantar el servicio.
# ---------------------------------------------------------------------------

resource "aws_ecs_service" "app" {
  name             = "bible-references-service"
  cluster          = aws_ecs_cluster.main.id
  task_definition  = aws_ecs_task_definition.app.arn
  desired_count    = 1
  platform_version = "LATEST"

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
    base              = 0
  }

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
    # Cada push a main despliega una revisión nueva desde GitHub Actions, y el
    # apagado nocturno mueve desired_count entre 0 y 1. Terraform no debe
    # revertir ninguna de las dos cosas.
    ignore_changes = [task_definition, desired_count]
  }
}
