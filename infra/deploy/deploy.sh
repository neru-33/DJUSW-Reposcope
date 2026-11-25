#!/bin/bash
# EC2에서 실행하는 배포 스크립트. api, worker, cloudflared를 지정한 이미지 태그로 띄운다.
#
#   sudo ./deploy.sh <이미지 태그>
#   sudo DB_HOST=<RDS 주소> GITHUB_APP_ID=<앱 ID> ./deploy.sh <이미지 태그>
#
# 하는 일(순서대로)
#   1. IMDS 차단 서비스와 DOCKER-USER 규칙이 있는지 확인한다. 없으면 멈춘다.
#   2. Parameter Store에서 비밀값(db-password, tunnel-token, github-app-key)을 읽는다. 초기값 CHANGE_ME이면 멈춘다.
#   3. ECR에 로그인해 이미지를 받는다(로그인 정보는 임시 폴더에 두고 끝나면 지운다).
#   4. worker를 먼저 시작하고, 마이그레이션이 끝나 worker_started가 로그에 나올 때까지 기다린다.
#   5. api와 cloudflared를 시작하고 api가 올라올 때까지 기다린다.
#
# 이전 버전으로 되돌리려면 이전 이미지 태그로 다시 실행한다. 태그는 덮어쓸 수 없으므로 같은 태그는 항상 같은 이미지다.
# 비밀값은 화면에 출력하지 않고 명령 인자로 넘기지 않는다(환경 변수로만 compose에 전달한다).

set -euo pipefail
# SSM Run Command로 실행하면 HOME이 없을 수 있다.
export HOME="${HOME:-/root}"

NAME_PREFIX="${NAME_PREFIX:-reposcope}"
DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$DEPLOY_DIR/compose.yml}"
SECRET_DIR="/etc/${NAME_PREFIX}/secrets"
# user_data의 IMDS 차단 규칙과 같은 값이다(infra/modules/app/templates/user_data.sh.tftpl).
EDGE_SUBNET="172.28.0.0/24"
IMDS_ADDR="169.254.169.254"
WORKER_WAIT_SECONDS="${WORKER_WAIT_SECONDS:-180}"
API_WAIT_SECONDS="${API_WAIT_SECONDS:-180}"
TUNNEL_WAIT_SECONDS="${TUNNEL_WAIT_SECONDS:-60}"

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] 오류: %s\n' "$*" >&2; exit 1; }

IMAGE_TAG="${1:-}"
[ -n "$IMAGE_TAG" ] || die "사용법: deploy.sh <이미지 태그>"
[ "$IMAGE_TAG" != "latest" ] || die "latest 태그는 쓰지 않는다"
[[ "$IMAGE_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "이미지 태그 형식이 올바르지 않다"

[ "$(id -u)" = "0" ] || die "root로 실행한다(sudo)"
[ -f "$COMPOSE_FILE" ] || die "compose 파일이 없다: $COMPOSE_FILE"
[ -n "${DB_HOST:-}" ] || die "DB_HOST가 필요하다(terraform output db_address 값)"
command -v docker >/dev/null || die "docker가 없다"
command -v aws >/dev/null || die "aws CLI가 없다"

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

# --- 1. IMDS 차단 확인 -------------------------------------------------------
# 차단 없이 api와 cloudflared가 뜨면 컨테이너가 EC2 역할의 자격 증명을 받을 수 있다. 확인되지 않으면 배포하지 않는다.
systemctl is-active --quiet "${NAME_PREFIX}-block-imds.service" \
  || die "${NAME_PREFIX}-block-imds.service가 active가 아니다"
iptables -C DOCKER-USER -s "$EDGE_SUBNET" -d "$IMDS_ADDR" -j DROP 2>/dev/null \
  || die "DOCKER-USER에 $EDGE_SUBNET → $IMDS_ADDR 차단 규칙이 없다"
log "IMDS 차단 규칙 확인"

# --- 2. 환경 정보와 비밀값 ----------------------------------------------------
if [ -z "${AWS_REGION:-}" ]; then
  imds_token="$(curl -fsS -m 5 -X PUT "http://${IMDS_ADDR}/latest/api/token" \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
  AWS_REGION="$(curl -fsS -m 5 -H "X-aws-ec2-metadata-token: ${imds_token}" \
    "http://${IMDS_ADDR}/latest/meta-data/placement/region")"
  unset imds_token
fi
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

db_ident="${NAME_PREFIX//-/_}"
DB_NAME="${DB_NAME:-$db_ident}"
DB_USER="${DB_USER:-$db_ident}"
QUEUE_URL="${QUEUE_URL:-https://sqs.${AWS_REGION}.amazonaws.com/${ACCOUNT_ID}/${NAME_PREFIX}-events}"
RAW_BUCKET="${RAW_BUCKET:-${NAME_PREFIX}-raw-events-${ACCOUNT_ID}}"
GITHUB_APP_ID="${GITHUB_APP_ID:-}"

get_param() {
  local value
  value="$(aws ssm get-parameter --name "/${NAME_PREFIX}/$1" --with-decryption \
    --query Parameter.Value --output text)" || die "Parameter Store에서 $1을 읽지 못했다"
  if [ -z "$value" ] || [ "$value" = "None" ] || [ "$value" = "CHANGE_ME" ]; then
    die "Parameter Store의 $1에 값이 없다(초기값 CHANGE_ME). 값을 먼저 넣는다"
  fi
  printf '%s' "$value"
}

DB_PASSWORD="$(get_param db-password)"
TUNNEL_TOKEN="$(get_param tunnel-token)"
app_key="$(get_param github-app-key)"
log "Parameter Store 값 확인(값은 출력하지 않는다)"

# worker 컨테이너(uid 65532)만 읽을 수 있게 둔다. 재부팅해도 컨테이너가 다시 뜰 수 있도록 디스크에 둔다.
install -d -m 0700 -o root -g root "$SECRET_DIR"
key_tmp="$(umask 077 && mktemp "$SECRET_DIR/.github-app-key.XXXXXX")"
printf '%s\n' "$app_key" > "$key_tmp"
chown root:65532 "$key_tmp"
chmod 0440 "$key_tmp"
mv -f "$key_tmp" "$SECRET_DIR/github-app-key"
unset app_key key_tmp
GITHUB_APP_KEY_FILE="$SECRET_DIR/github-app-key"

# --- 3. 이미지 받기 ----------------------------------------------------------
# ECR 로그인 정보는 임시 폴더에 두고 끝나면 지운다.
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
API_IMAGE="${REGISTRY}/${NAME_PREFIX}-api:${IMAGE_TAG}"
WORKER_IMAGE="${REGISTRY}/${NAME_PREFIX}-worker:${IMAGE_TAG}"
APP_VERSION="$IMAGE_TAG"

export API_IMAGE WORKER_IMAGE APP_VERSION \
  DB_HOST DB_NAME DB_USER DB_PASSWORD QUEUE_URL RAW_BUCKET GITHUB_APP_ID GITHUB_APP_KEY_FILE TUNNEL_TOKEN

compose config -q
log "이미지 받기: ${NAME_PREFIX}-api, ${NAME_PREFIX}-worker, cloudflared (태그 ${IMAGE_TAG})"
compose pull

# --- 4. worker: 마이그레이션이 끝날 때까지 기다린다 ----------------------------
wait_for_log() { # <서비스> <찾을 글자> <제한 시간(초)>
  local service="$1" pattern="$2" limit="$3" elapsed=0 logs state cid
  while [ "$elapsed" -lt "$limit" ]; do
    logs="$(compose logs --no-color "$service" 2>&1 || true)"
    case "$logs" in *"$pattern"*) return 0 ;; esac
    cid="$(compose ps -q "$service" 2>/dev/null || true)"
    state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo missing)"
    case "$state" in
      running|created) ;;
      *) printf '%s\n' "$logs" | tail -n 20 >&2; die "$service 컨테이너가 $state 상태다" ;;
    esac
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

# 로그가 나온 뒤에 컨테이너가 죽거나 계속 다시 시작되는 경우를 잡는다.
ensure_running() { # <서비스>
  local service="$1" cid state
  cid="$(compose ps -q "$service" 2>/dev/null || true)"
  state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo missing)"
  if [ "$state" != "running" ]; then
    compose logs --no-color --tail 20 "$service" >&2 || true
    die "$service 컨테이너가 $state 상태다"
  fi
}

# 키 파일이나 비밀값이 바뀌었을 수 있으므로 worker는 항상 다시 만든다.
log "worker 시작(마이그레이션 적용)"
compose up -d --force-recreate --no-deps worker
if ! wait_for_log worker "worker_started" "$WORKER_WAIT_SECONDS"; then
  compose logs --no-color --tail 20 worker >&2 || true
  die "worker가 ${WORKER_WAIT_SECONDS}초 안에 worker_started를 남기지 않았다"
fi
sleep 3
ensure_running worker
log "worker 시작 완료"

# --- 5. api와 cloudflared ----------------------------------------------------
log "api, cloudflared 시작"
compose up -d --no-deps api cloudflared
if ! wait_for_log api "Started " "$API_WAIT_SECONDS"; then
  compose logs --no-color --tail 20 api >&2 || true
  die "api가 ${API_WAIT_SECONDS}초 안에 시작하지 않았다"
fi
sleep 3
ensure_running api
log "api 시작 완료"

# 터널 연결 로그가 없으면 경고만 한다. 공개 주소 확인(/api/version)은 호출한 쪽에서 한다.
if wait_for_log cloudflared "Registered tunnel connection" "$TUNNEL_WAIT_SECONDS"; then
  log "터널 연결 확인"
else
  compose logs --no-color --tail 20 cloudflared >&2 || true
  log "경고: ${TUNNEL_WAIT_SECONDS}초 안에 터널 연결 로그가 나오지 않았다. 터널 토큰과 Cloudflare 상태를 확인한다"
fi

# 만든 지 7일이 지났고 지금 컨테이너가 쓰지 않는 이미지만 지운다(until은 이미지를 만든 시각 기준). 이전 태그는 ECR에서 다시 받을 수 있다.
docker image prune -af --filter "until=168h" >/dev/null || true

ensure_running cloudflared
compose ps
log "배포 완료: ${IMAGE_TAG}"
