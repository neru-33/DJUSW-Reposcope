variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "alert_emails" {
  description = "예산 알림을 받을 이메일 목록(팀 공용 주소)"
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "월 예산 상한(USD). 이상 징후를 일찍 알아채기 위한 값이다"
  type        = number
  default     = 5
}
