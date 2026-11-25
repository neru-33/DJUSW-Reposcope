variable "name_prefix" {
  description = "저장소 이름 앞에 붙는 접두어"
  type        = string
}

variable "repositories" {
  description = "만들 저장소 이름 목록. 실제 이름은 <접두어>-<이름>이다"
  type        = set(string)
  default     = ["api", "worker"]
}

variable "keep_images" {
  description = "저장소마다 남겨 둘 최근 이미지 수. 넘으면 오래된 것부터 지운다"
  type        = number
  default     = 10
}

variable "force_delete" {
  description = "인프라를 종료할 때만 true. 이미지가 남아 있어도 저장소를 삭제한다"
  type        = bool
  default     = false
}
