output "names" {
  description = "파라미터 이름. 키는 params의 키와 같다"
  value       = { for k, p in aws_ssm_parameter.this : k => p.name }
}

output "arns" {
  description = "파라미터 ARN. IAM 권한에서 자원을 지정할 때 쓴다"
  value       = { for k, p in aws_ssm_parameter.this : k => p.arn }
}
