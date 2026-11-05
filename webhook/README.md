# webhook

GitHub Webhook을 수신하는 AWS Lambda 코드를 보관한다.

- 서명을 검증하고 원본 payload를 S3에, 처리 메시지를 SQS에 전달한다.
- 소스코드와 테스트 코드는 이 폴더 안에 둔다.
