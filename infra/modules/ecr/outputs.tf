output "repository_urls" {
  description = "저장소 주소(이미지 이름). 키는 api, worker"
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  description = "저장소 ARN 목록(권한 정책에 쓴다)"
  value       = [for r in aws_ecr_repository.this : r.arn]
}
