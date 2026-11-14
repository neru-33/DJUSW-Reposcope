variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "subnet_ids" {
  description = "프라이빗 서브넷 2개(서로 다른 AZ)"
  type        = list(string)
}

variable "security_group_id" {
  description = "RDS 보안 그룹"
  type        = string
}

variable "availability_zone" {
  description = "DB 인스턴스가 놓일 AZ. EC2와 같게 둔다"
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL 메이저 버전. 로컬 실행 환경과 같게 17"
  type        = string
  default     = "17"
}

variable "db_name" {
  description = "초기 데이터베이스 이름. envs/prod가 name_prefix에서 만들어 넘기며, API와 worker는 terraform output db_name 값을 환경 변수로 받는다"
  type        = string
}

variable "db_username" {
  description = "마스터 사용자 이름. envs/prod가 name_prefix에서 만들어 넘긴다"
  type        = string
}

variable "db_password" {
  description = "마스터 비밀번호. Parameter Store의 db-password 값을 넘기며 state에 저장되지 않는다(ephemeral, write-only)"
  type        = string
  sensitive   = true
  ephemeral   = true
  default     = null
}

variable "password_version" {
  description = "비밀번호를 바꿀 때만 1 올린다"
  type        = number
  default     = 1
}

variable "deletion_protection" {
  description = "삭제 보호. 인프라를 종료(destroy)할 때만 false로 바꾼다"
  type        = bool
  default     = true
}
