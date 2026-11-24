variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "region" {
  description = "AWS 리전"
  type        = string
}

variable "admin_ssh_cidrs" {
  description = "SSH 22를 허용할 관리자 IP 목록(예: [\"1.2.3.4/32\"]). 비우면 SSH를 닫고 SSM Session Manager로 접속한다"
  type        = list(string)
  default     = []
}
