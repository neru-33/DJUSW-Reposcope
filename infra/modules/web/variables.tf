variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "domain" {
  description = "루트 도메인. 화면은 www.<도메인>으로 제공한다"
  type        = string
}

variable "zone_id" {
  description = "Cloudflare 영역(도메인) ID. www와 ACM 확인용 DNS 레코드를 만든다"
  type        = string
}

variable "force_destroy" {
  description = "인프라를 종료할 때만 true. 웹 버킷에 파일이 있어도 삭제한다"
  type        = bool
  default     = false
}
