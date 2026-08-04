resource "aws_cloudfront_origin_access_control" "frontend" {
  name = "biblia-frontend-oac"
  # Vacío a propósito: el proveedor pone "Managed by Terraform" si se omite, y
  # el recurso real no tiene descripción.
  description                       = ""
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# IDs de políticas gestionadas por AWS, para que se lean por nombre y no por UUID.
locals {
  cache_policy_caching_optimized  = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  cache_policy_caching_disabled   = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
  origin_request_policy_all_viewer = "b689b0a8-53d0-40ab-baf2-68738e2966ac"

  s3_origin_id  = "biblia-frontend-prod.s3.mx-central-1.amazonaws.com-mnpm2ibw6rj"
  alb_origin_id = "Backend-API"
}

# ---------------------------------------------------------------------------
# Distribución
#
# Sirve dos cosas distintas desde el mismo dominio:
#   - el bundle estático desde S3 (comportamiento por defecto)
#   - la API desde el ALB (comportamiento /api/*)
#
# Que compartan origen es la razón de que CORS no intervenga en el camino de
# producción: para el navegador todo es escrituraclave.com.
# ---------------------------------------------------------------------------

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2"
  price_class         = "PriceClass_All"
  default_root_object = "index.html"
  comment             = ""
  aliases             = ["www.escrituraclave.com", "escrituraclave.com"]
  web_acl_id          = var.waf_web_acl_arn

  # Único tag que existe en toda la cuenta. El nombre es engañoso: la
  # distribución ya no sirve solo S3, también enruta /api/* al ALB.
  tags = {
    Name = "S3-biblia-frontend"
  }

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  # ---------------------------------------------------------------------------
  # Origen del backend — HALLAZGO 2, FASE 2
  #
  # El header secreto viaja en cada petición al ALB, que lo exige mediante una
  # regla del listener. Es lo que impide que otra distribución de CloudFront,
  # de cualquier cuenta, use este ALB como origen: la restricción por prefix
  # list sola no distingue una distribución de otra.
  # ---------------------------------------------------------------------------
  origin {
    origin_id   = local.alb_origin_id
    domain_name = aws_lb.main.dns_name

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only" # tramo sin cifrar, pendiente
      origin_ssl_protocols     = ["SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2"]
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
      ip_address_type          = "ipv4"
    }

    custom_header {
      name  = "X-Origin-Verify"
      value = var.origin_verify_secret
    }
  }

  default_cache_behavior {
    target_origin_id       = local.s3_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["HEAD", "GET"]
    cached_methods         = ["HEAD", "GET"]
    cache_policy_id        = local.cache_policy_caching_optimized
    compress               = true
  }

  # La API no se cachea y reenvía todo lo del visor al origen.
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.alb_origin_id
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["HEAD", "DELETE", "POST", "GET", "OPTIONS", "PUT", "PATCH"]
    cached_methods           = ["HEAD", "GET"]
    cache_policy_id          = local.cache_policy_caching_disabled
    origin_request_policy_id = local.origin_request_policy_all_viewer
    compress                 = true
  }

  # Enrutado del lado del cliente: cualquier ruta desconocida devuelve index.html
  # con 200 para que el router del frontend la resuelva.
  custom_error_response {
    error_code            = 403
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 10
  }

  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/index.html"
    error_caching_min_ttl = 10
  }

  viewer_certificate {
    acm_certificate_arn      = var.acm_certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.3_2025"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }
}
