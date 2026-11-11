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
