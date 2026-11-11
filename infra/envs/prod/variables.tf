variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
  default     = "reposcope"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "admin_ssh_cidrs" {
  description = "SSH 허용 IP. 기본은 닫힘이고 SSM Session Manager로 접속한다"
  type        = list(string)
  default     = []
}

variable "rds_deletion_protection" {
  description = "RDS 삭제 보호. 인프라를 종료(destroy)할 때만 false로 바꾼다"
  type        = bool
  default     = true
}

variable "db_password" {
  description = "RDS 마스터 비밀번호. Parameter Store의 db-password 값을 TF_VAR_db_password로 넘기며 state에 저장되지 않는다"
  type        = string
  sensitive   = true
  ephemeral   = true
  default     = null
}

variable "raw_bucket_name" {
  description = "원본 이벤트 S3 버킷 이름. 전 세계에서 하나뿐이어야 하므로 충돌하면 바꾼다"
  type        = string
  default     = "reposcope-raw-events"
}

variable "alert_email" {
  description = "알람과 예산 알림을 받을 팀 공용 이메일"
  type        = string
}

variable "monthly_budget_usd" {
  description = "월 예산 상한(USD)"
  type        = number
  default     = 5
}

variable "instance_type" {
  description = "EC2 크기"
  type        = string
  default     = "t3.small"
}

variable "raw_force_destroy" {
  description = "인프라를 종료할 때만 true. 원본 버킷에 객체가 있어도 삭제한다"
  type        = bool
  default     = false
}
