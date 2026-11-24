output "tunnel_id" {
  description = "터널 ID. Cloudflare 대시보드에서 토큰을 복사할 터널을 찾을 때 쓴다"
  value       = local.tunnel_id
}

output "api_hostname" {
  value = local.api_hostname
}
