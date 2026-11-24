variable "zone_id" {
  description = "Cloudflare 영역(도메인) ID"
  type        = string
}

variable "account_id" {
  description = "Cloudflare 계정 ID"
  type        = string
}

variable "domain" {
  description = "루트 도메인"
  type        = string
}

variable "name_prefix" {
  description = "터널 이름에 쓰는 접두어"
  type        = string
}

variable "tunnel_id" {
  description = "이미 만들어 둔 터널의 ID. null이면 Terraform이 터널을 만든다. 터널 비밀값이 state에 남으면 Cloudflare 대시보드에서 터널을 만들고 이 값을 넣는다"
  type        = string
  default     = null
}
