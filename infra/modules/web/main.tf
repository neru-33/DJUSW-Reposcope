# 화면(React 정적 파일): S3 웹 버킷 → CloudFront → www.<도메인>.
# 버킷은 비공개이고 CloudFront(OAC)만 읽는다. 인증서는 ACM(us-east-1, CloudFront 요건)이고
# 확인용 DNS 레코드와 www 레코드는 Cloudflare에 만든다.
#
# 화면 파일 업로드와 캐시 무효화는 이 모듈이 하지 않는다(배포 자동화, #75).

data "aws_caller_identity" "current" {}

locals {
  www_hostname = "www.${var.domain}"
  bucket_name  = "${var.name_prefix}-web-${data.aws_caller_identity.current.account_id}"
}

# ---------------------------------------------------------------- 웹 버킷
resource "aws_s3_bucket" "web" {
  bucket        = local.bucket_name
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 이 배포(distribution)에서 오는 읽기만 허용한다.
resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontRead"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.web.arn}/*"
      Condition = {
        StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.web.arn }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.web]
}

# ---------------------------------------------------------------- 인증서(ACM, us-east-1)
resource "aws_acm_certificate" "www" {
  provider = aws.us_east_1

  domain_name       = local.www_hostname
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "cloudflare_dns_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.www.domain_validation_options : dvo.domain_name => {
      name  = trimsuffix(dvo.resource_record_name, ".")
      type  = dvo.resource_record_type
      value = trimsuffix(dvo.resource_record_value, ".")
    }
  }

  zone_id = var.zone_id
  name    = each.value.name
  type    = each.value.type
  content = each.value.value
  proxied = false
  ttl     = 1 # 자동
  comment = "ACM validation (managed by Terraform)"
}

# 인증서가 발급될 때까지 기다린다(보통 몇 분).
resource "aws_acm_certificate_validation" "www" {
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.www.arn
  validation_record_fqdns = [for dvo in aws_acm_certificate.www.domain_validation_options : trimsuffix(dvo.resource_record_name, ".")]

  depends_on = [cloudflare_dns_record.cert_validation]
}

# ---------------------------------------------------------------- CloudFront
data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_response_headers_policy" "security" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${var.name_prefix}-web"
  description                       = "Access to the web bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.name_prefix} web"
  default_root_object = "index.html"
  aliases             = [local.www_hostname]
  price_class         = "PriceClass_200" # 아시아 엣지를 포함한다
  http_version        = "http2and3"

  origin {
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_id                = "web-bucket"
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  default_cache_behavior {
    target_origin_id           = "web-bucket"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security.id
  }

  # React Router 주소(예: /tabs/activity)를 새로고침하면 S3에 그 파일이 없어 403/404가 온다.
  # index.html을 200으로 돌려주면 React Router가 주소를 읽어 화면을 그린다.
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

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.www.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# ---------------------------------------------------------------- DNS
resource "cloudflare_dns_record" "www" {
  zone_id = var.zone_id
  name    = local.www_hostname
  type    = "CNAME"
  content = aws_cloudfront_distribution.web.domain_name
  proxied = false # Cloudflare를 거치지 않고 CloudFront가 직접 받는다
  ttl     = 1     # 자동
  comment = "www to CloudFront (managed by Terraform)"
}
