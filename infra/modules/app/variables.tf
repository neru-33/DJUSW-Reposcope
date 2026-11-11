variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "region" {
  description = "AWS 리전"
  type        = string
}

variable "instance_type" {
  description = "EC2 크기. 프리 티어 대상 여부를 계정에서 확인한 뒤 정한다"
  type        = string
  default     = "t3.small"
}

variable "subnet_id" {
  description = "EC2를 둘 퍼블릭 서브넷"
  type        = string
}

variable "security_group_id" {
  description = "EC2에 붙일 보안 그룹"
  type        = string
}

variable "raw_bucket_name" {
  description = "원본 이벤트 버킷 이름. EC2가 읽기만 한다"
  type        = string
}

variable "queue_arn" {
  description = "이벤트 큐 ARN"
  type        = string
}

variable "dlq_arn" {
  description = "DLQ ARN. 상태 조회만 허용한다"
  type        = string
}

variable "secret_param_arns" {
  description = "EC2가 읽을 Parameter Store ARN 목록(webhook-secret 제외)"
  type        = list(string)
}

variable "webhook_secret_param_arn" {
  description = "EC2에서 읽지 못하게 명시적으로 막을 webhook-secret ARN. 수신 Lambda 전용이다"
  type        = string
}

variable "compose_version" {
  description = "Docker Compose 플러그인 버전"
  type        = string
  default     = "v2.29.7"
}
