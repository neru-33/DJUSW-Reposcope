# Cloudflare: 터널, api 공개 주소와 DNS, Always Use HTTPS, 로그인 요청 제한.
# www 레코드와 ACM 확인용 레코드는 CloudFront와 ACM이 정해 주는 값이 필요하므로 웹 구성에서 만든다.
#
# 터널 토큰은 Terraform이 읽지도 만들지도 않는다. tunnel_secret을 넣지 않고, 토큰을 가져오는 데이터 소스도 쓰지 않는다.
# 운영자가 Cloudflare 대시보드에서 토큰을 복사해 Parameter Store의 tunnel-token에 넣는다.

locals {
  api_hostname = "api.${var.domain}"
  create       = var.tunnel_id == null
  tunnel_id    = local.create ? cloudflare_zero_trust_tunnel_cloudflared.this[0].id : var.tunnel_id
}

resource "cloudflare_zero_trust_tunnel_cloudflared" "this" {
  count = local.create ? 1 : 0

  account_id = var.account_id
  name       = "${var.name_prefix}-api"
  config_src = "cloudflare"
}

# 공개 주소: api.<도메인> → 같은 Docker 네트워크의 api 컨테이너
resource "cloudflare_zero_trust_tunnel_cloudflared_config" "this" {
  account_id = var.account_id
  tunnel_id  = local.tunnel_id

  config = {
    ingress = [
      {
        hostname = local.api_hostname
        service  = "http://api:8080"
      },
      {
        service = "http_status:404"
      },
    ]
  }
}

# 터널은 프록시가 켜져 있어야 동작한다.
resource "cloudflare_dns_record" "api" {
  zone_id = var.zone_id
  name    = local.api_hostname
  type    = "CNAME"
  content = "${local.tunnel_id}.cfargotunnel.com"
  proxied = true
  ttl     = 1 # 자동
  comment = "api tunnel (managed by Terraform)"
}

resource "cloudflare_zone_setting" "always_use_https" {
  zone_id    = var.zone_id
  setting_id = "always_use_https"
  value      = "on"
}

# 무료 플랜은 요청 제한 규칙을 1개 쓸 수 있고, 규칙 조건에는 경로만 쓸 수 있다(메서드는 Business부터).
# characteristics의 cf.colo.id는 필수이다. 관리자 로그인에 IP당 10초 10회를 넘으면 10초 차단한다.
resource "cloudflare_ruleset" "rate_limit" {
  zone_id     = var.zone_id
  name        = "${var.name_prefix}-rate-limit"
  description = "Limit admin login attempts"
  kind        = "zone"
  phase       = "http_ratelimit"

  rules = [
    {
      description = "/auth/admin/login: 10 requests per 10s per IP"
      action      = "block"
      enabled     = true
      expression  = "(http.request.uri.path eq \"/auth/admin/login\")"
      ratelimit = {
        characteristics     = ["cf.colo.id", "ip.src"]
        period              = 10
        requests_per_period = 10
        mitigation_timeout  = 10
      }
    },
  ]
}
