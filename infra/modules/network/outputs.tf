output "vpc_id" {
  value = aws_vpc.this.id
}

output "availability_zone" {
  description = "EC2와 RDS가 놓이는 AZ"
  value       = local.az_a
}

output "public_subnet_id" {
  value = aws_subnet.public_a.id
}

output "private_subnet_ids" {
  value = [aws_subnet.private_a.id, aws_subnet.private_c.id]
}

output "app_security_group_id" {
  value = aws_security_group.app.id
}

output "db_security_group_id" {
  value = aws_security_group.db.id
}
