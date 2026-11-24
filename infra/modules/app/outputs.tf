output "instance_id" {
  value = aws_instance.app.id
}

output "role_name" {
  value = aws_iam_role.app.name
}
