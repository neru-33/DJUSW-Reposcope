output "vpc_id" {
  value = module.network.vpc_id
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "secret_names" {
  description = "비밀값을 넣을 Parameter Store 파라미터 이름"
  value       = module.secrets.names
}

output "db_address" {
  description = "RDS 호스트 이름(API와 worker의 DB_HOST)"
  value       = module.data.address
}

output "db_name" {
  value = module.data.db_name
}
