# api와 worker 컨테이너 이미지 저장소.
#
# 태그는 덮어쓸 수 없다(IMMUTABLE). 같은 태그는 항상 같은 이미지이므로 이전 태그로 다시 배포하면 되돌릴 수 있다.
# 최근 이미지 keep_images개만 남기고 지워 저장 요금이 계속 늘지 않게 한다.

resource "aws_ecr_repository" "this" {
  for_each = var.repositories

  name                 = "${var.name_prefix}-${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the latest ${var.keep_images} images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.keep_images
      }
      action = { type = "expire" }
    }]
  })
}
