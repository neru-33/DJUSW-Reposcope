output "role_arn" {
  description = "GitHub Actions 워크플로의 role-to-assume 값"
  value       = aws_iam_role.deploy.arn
}

output "role_name" {
  value = aws_iam_role.deploy.name
}

output "provider_arn" {
  value = local.provider_arn
}
