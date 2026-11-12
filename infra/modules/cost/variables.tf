variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "alert_emails" {
  description = "예산 알림을 받을 이메일 목록(팀 공용 주소)"
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "월 예산 상한(USD). 크레딧을 빼지 않은 사용량 기준이며, 예상 월 비용(약 43달러)보다 조금 높게 잡는다"
  type        = number
  default     = 50
}
