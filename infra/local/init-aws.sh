#!/bin/sh
# moto에 S3, SQS, SSM 파라미터를 만든다. 여러 번 실행해도 안전하다.
# 큐 설정은 운영과 같다: 가시성 타임아웃 60초, 롱 폴링 20초, 5회 실패 시 DLQ.
set -eu

: "${LOCAL_WEBHOOK_SECRET:?.env에 LOCAL_WEBHOOK_SECRET을 설정한다}"
: "${DB_PASSWORD:?.env에 DB_PASSWORD를 설정한다}"

PREFIX="reposcope"
REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"

i=0
until aws s3api list-buckets >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "moto에 연결하지 못했다" >&2
    exit 1
  fi
  sleep 1
done

BUCKET="$PREFIX-raw-events"
aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1 ||
  aws s3api create-bucket --bucket "$BUCKET" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null

DLQ_URL="$(aws sqs create-queue --queue-name "$PREFIX-events-dlq" \
  --attributes MessageRetentionPeriod=1209600 \
  --query QueueUrl --output text)"
DLQ_ARN="$(aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)"

cat > /tmp/queue-attributes.json <<EOF
{
  "VisibilityTimeout": "60",
  "ReceiveMessageWaitTimeSeconds": "20",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"5\"}"
}
EOF
aws sqs create-queue --queue-name "$PREFIX-events" \
  --attributes file:///tmp/queue-attributes.json >/dev/null

put_param() {
  aws ssm put-parameter --name "/$PREFIX/$1" --type SecureString \
    --overwrite --value "$2" >/dev/null
}
put_param webhook-secret "$LOCAL_WEBHOOK_SECRET"
put_param db-password "$DB_PASSWORD"
put_param github-app-key CHANGE_ME

echo "로컬 AWS 준비 완료: S3 $BUCKET, SQS $PREFIX-events(+DLQ), SSM /$PREFIX/* 3개"
