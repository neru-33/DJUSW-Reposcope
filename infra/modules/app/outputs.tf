output "instance_id" {
  value = aws_instance.app.id
}

output "public_ip" {
  value = aws_eip.app.public_ip
}

output "public_dns" {
  description = "EIP의 퍼블릭 DNS. 앞단(CloudFront 등)의 오리진 주소로 쓴다"
  value       = aws_eip.app.public_dns
}

output "role_name" {
  value = aws_iam_role.app.name
}
