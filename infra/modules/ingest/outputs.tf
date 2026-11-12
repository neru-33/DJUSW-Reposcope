output "webhook_url" {
  description = "GitHub App Webhook URL에 입력할 값"
  value       = "${aws_apigatewayv2_api.webhook.api_endpoint}/webhook"
}

output "queue_url" {
  value = aws_sqs_queue.events.url
}

output "queue_arn" {
  value = aws_sqs_queue.events.arn
}

output "dlq_url" {
  value = aws_sqs_queue.dlq.url
}

output "raw_bucket_name" {
  value = aws_s3_bucket.raw.bucket
}

output "raw_bucket_arn" {
  value = aws_s3_bucket.raw.arn
}

output "alerts_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}
