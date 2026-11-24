output "www_url" {
  value = "https://${local.www_hostname}"
}

output "bucket_name" {
  description = "화면 파일을 올리는 웹 버킷"
  value       = aws_s3_bucket.web.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.web.arn
}

output "distribution_id" {
  description = "캐시 무효화에 쓰는 CloudFront 배포 ID"
  value       = aws_cloudfront_distribution.web.id
}

output "distribution_arn" {
  value = aws_cloudfront_distribution.web.arn
}
