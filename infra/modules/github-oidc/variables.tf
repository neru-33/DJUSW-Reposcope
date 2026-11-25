variable "name_prefix" {
  description = "자원 이름 앞에 붙는 접두어"
  type        = string
}

variable "region" {
  description = "AWS 리전"
  type        = string
}

variable "github_repository" {
  description = "배포 역할을 맡을 수 있는 저장소(<소유자>/<이름>). 저장소 이름이 바뀌거나 조직으로 옮기면 함께 바꾼다"
  type        = string
}

variable "github_branch" {
  description = "배포 역할을 맡을 수 있는 브랜치. 이 브랜치의 워크플로만 AWS에 들어온다"
  type        = string
  default     = "main"
}

variable "oidc_provider_arn" {
  description = "이미 계정에 있는 GitHub OIDC provider의 ARN. 계정에 하나만 만들 수 있으므로 있으면 넣고, null이면 새로 만든다"
  type        = string
  default     = null
}

variable "ecr_repository_arns" {
  description = "이미지를 올릴 ECR 저장소 ARN 목록"
  type        = list(string)
}

variable "instance_id" {
  description = "배포 명령(SSM Run Command)을 보낼 EC2 인스턴스 ID. 이 한 대에만 보낼 수 있다"
  type        = string
}
