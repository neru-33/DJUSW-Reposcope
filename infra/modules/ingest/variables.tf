variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "raw_bucket_name" {
  description = "원본 이벤트를 저장할 S3 버킷 이름. 전 세계에서 하나뿐이어야 하므로 충돌하면 접미사를 붙인다"
  type        = string
}

variable "raw_retention_days" {
  description = "원본 이벤트 보관 일수"
  type        = number
  default     = 90
}

variable "force_destroy" {
  description = "true면 버킷에 객체가 있어도 삭제한다. 인프라를 종료할 때만 true로 바꾼다"
  type        = bool
  default     = false
}

variable "webhook_secret_param_name" {
  description = "Webhook 서명 검증에 쓰는 Parameter Store 이름"
  type        = string
}

variable "webhook_secret_param_arn" {
  description = "위 파라미터의 ARN. Lambda가 이 파라미터만 읽을 수 있게 한다"
  type        = string
}

variable "alert_email" {
  description = "알람을 받을 이메일(팀 공용 주소)"
  type        = string
}

variable "lambda_source_dir" {
  description = "수신 Lambda 소스코드 디렉터리(저장소의 webhook/)"
  type        = string
}

variable "lambda_zip_path" {
  description = "압축한 Lambda 코드를 저장할 경로"
  type        = string
}

variable "lambda_runtime" {
  description = "Lambda 런타임. webhook/ 코드의 언어에 맞춘다"
  type        = string
  default     = "python3.13"
}

variable "lambda_handler" {
  description = "Lambda 진입점. webhook/ 코드의 파일과 함수 이름에 맞춘다"
  type        = string
  default     = "handler.handler"
}
