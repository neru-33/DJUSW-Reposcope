output "address" {
  description = "DB 호스트 이름. API와 worker의 DB_HOST에 쓴다"
  value       = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "username" {
  value = aws_db_instance.this.username
}
