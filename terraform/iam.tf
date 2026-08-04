# ---------------------------------------------------------------------------
# Rol de ejecución de las tasks — HALLAZGO 5, SIN RESOLVER
#
# Este rol se usa a la vez como execution role (lo asume el agente de ECS para
# bajar la imagen y leer secretos) y como task role (lo asume la aplicación en
# runtime). Deberían ser dos roles distintos: la aplicación no necesita ni pull
# de ECR ni acceso a Secrets Manager una vez arrancada.
#
# Agrava el problema la política adjunta SecretsManagerReadWrite, que es
# demasiado amplia: concede lectura Y ESCRITURA sobre TODOS los secretos de la
# cuenta, no solo sobre bible-references/db. Sustituirla por una política propia
# con `secretsmanager:GetSecretValue` limitada al ARN de ese secreto.
# ---------------------------------------------------------------------------

resource "aws_iam_role" "ecs_task_execution" {
  name                 = "ecsTaskExecutionRole"
  path                 = "/"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2008-10-17"
    Statement = [
      {
        Sid       = ""
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Demasiado amplia. Ver el comentario de arriba.
resource "aws_iam_role_policy_attachment" "secrets_manager_read_write" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
}
