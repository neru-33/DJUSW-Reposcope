#!/bin/sh
# api와 cloudflared가 쓰는 edge 네트워크(172.28.0.0/24)에서 EC2 인스턴스 메타데이터(IMDS)로 가는 요청을 막는다.
# EC2 역할에는 GitHub App 키를 읽는 권한이 있으므로 이 두 컨테이너가 자격 증명을 받으면 안 된다.
# 여러 번 실행해도 규칙은 하나만 만든다. 재부팅하면 규칙이 사라지므로 배포할 때마다 compose를 띄우기 전에 실행한다.
set -eu

EDGE_SUBNET=172.28.0.0/24
IMDS=169.254.169.254

# DOCKER-USER 체인은 Docker가 시작할 때 만든다.
iptables -nL DOCKER-USER >/dev/null

if ! iptables -C DOCKER-USER -s "$EDGE_SUBNET" -d "$IMDS" -j DROP 2>/dev/null; then
  iptables -I DOCKER-USER 1 -s "$EDGE_SUBNET" -d "$IMDS" -j DROP
fi
echo "IMDS 차단 규칙 적용: $EDGE_SUBNET -> $IMDS"
