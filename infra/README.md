# infra

AWS 구성과 배포 설정을 보관한다.

- [로컬 실행 환경](local/README.md): docker compose로 postgres, api, moto를 실행한다.
- 비밀값과 키 파일은 이 폴더에도 올리지 않는다.

## AWS 구성(Terraform)

AWS 자원은 Terraform으로만 만들고 바꾼다. `terraform apply`는 infra 담당자만 실행한다.

| 폴더 | 내용 |
|---|---|
| `bootstrap/` | Terraform state를 저장할 S3 버킷. 계정당 한 번만 실행한다. |
| `envs/prod/` | 운영 환경. 아래 모듈을 연결한다. |
| `modules/network` | VPC, 서브넷, 보안 그룹 |
| `modules/secrets` | Parameter Store 비밀값 껍데기(실제 값은 Terraform 밖에서 넣는다) |
| `modules/data` | RDS PostgreSQL |
| `modules/ingest` | S3 원본 버킷, SQS와 DLQ, 수신 Lambda, API Gateway, 알람 |
| `modules/app` | EC2, IAM 역할 |
| `modules/cost` | 예산 알림 |

### 이름 규칙

자원 이름은 `name_prefix`(기본값 `reposcope`)에서 만든다. 이름을 바꾸려면 모든 자원 이름이 맞도록 `bootstrap/`과 `envs/prod/`의 `name_prefix`를 같은 값으로 정한다. 값은 소문자로 시작하고 소문자, 숫자, 하이픈만 쓰는 20자 이하로 한다.

| 자원 | 이름 |
|---|---|
| state 버킷 | `<name_prefix>-tfstate-<계정 ID>` |
| 원본 이벤트 버킷 | `<name_prefix>-raw-events-<계정 ID>` (`raw_bucket_name`으로 바꿀 수 있다) |
| DB 이름과 사용자 | `name_prefix`의 `-`를 `_`로 바꾼 값(PostgreSQL 이름에는 `-`를 쓸 수 없다). API와 worker는 `terraform output db_name` 값을 환경 변수로 받는다 |
| Parameter Store 경로 | `/<name_prefix>/<이름>` |
| EC2 부팅 로그 | `/var/log/<name_prefix>-bootstrap.log` |

아래 설명의 `/reposcope/...` 경로는 기본값을 쓴 예시다.

### 처음 만들 때

1. state 버킷을 만든다.

   ```bash
   cd infra/bootstrap
   terraform init
   terraform apply
   ```

2. `infra/envs/prod`에서 init한다. 버킷 이름은 1번의 출력값이다.

   ```bash
   cd infra/envs/prod
   terraform init -backend-config="bucket=<state 버킷 이름>"
   ```

3. `terraform.tfvars.example`을 `terraform.tfvars`로 복사해 `alert_email`을 채운다.
4. RDS 비밀번호를 환경 변수로 넘긴다. Parameter Store의 `/reposcope/db-password`에 넣을 값과 같아야 하며 16자 이상이다. `plan`, `apply`, `destroy`를 실행할 때마다 필요하므로, 새 터미널에서는 다시 설정한다.

   ```powershell
   $env:TF_VAR_db_password = "<16자 이상 비밀번호>"
   ```

5. `terraform plan`으로 결과를 확인한 뒤 `terraform apply`를 실행한다.
6. 비밀값을 Parameter Store에 넣는다. Terraform은 값을 읽지도 바꾸지도 않는다.

   ```bash
   aws ssm put-parameter --name /reposcope/<이름> --type SecureString --overwrite --value "<값>"
   ```

   GitHub App의 Private key(PEM)는 여러 줄이라 `--value "<값>"`로 넣을 수 없다. 파일로 넣는다.

   ```bash
   aws ssm put-parameter --name /reposcope/github-app-key --type SecureString --overwrite --value file://<pem 경로>
   ```

7. 알림 이메일로 도착한 SNS 구독 확인 메일의 `Confirm subscription`을 누른다.
8. `terraform output webhook_url` 값을 GitHub App의 Webhook URL에 넣는다([GitHub App 설정](../docs/github-app.md)).

수신 Lambda 코드는 `webhook/src/`에 있고, apply할 때 이 디렉터리를 압축해 배포한다. 테스트와 로컬 실행 파일은 배포 파일에 들어가지 않는다.

### 컨테이너와 EC2 역할

EC2 역할에는 GitHub App 키를 읽는 권한이 있다. 인스턴스 메타데이터의 hop limit이 2여서 EC2 위의 컨테이너는 이 역할의 자격 증명을 받을 수 있다. 이 설정은 worker 컨테이너용이다.

- api와 cloudflared 컨테이너는 배포 설정에서 IMDS 접근을 막는다.
- api가 쓰는 비밀값(DB 비밀번호, GitHub Client secret)은 컨테이너가 직접 읽지 않는다. 배포 스크립트가 Parameter Store에서 읽어 환경 변수로 넘긴다.

### 운영 배포 설정

`infra/deploy/compose.yml`이 EC2에서 api와 `cloudflared`를 실행한다. worker와 배포 자동화(CD)는 #75에서 추가한다.

| 항목 | 내용 |
|---|---|
| api | `API_IMAGE`로 받은 이미지. 8080은 호스트에 열지 않고 `expose`만 한다 |
| cloudflared | Cloudflare로 먼저 연결해 터널을 만든다. 요청은 `http://api:8080`으로 넘긴다. 자동으로 다시 시작한다 |
| api 환경 변수 | `SESSION_COOKIE_SECURE=true`, `CORS_ALLOWED_ORIGIN`과 `FRONTEND_URL`은 `https://www.eclipse-sync.com` |
| 비밀값 | `DB_PASSWORD`, `TUNNEL_TOKEN`은 파일에 쓰지 않는다. 배포 스크립트가 Parameter Store에서 읽어 환경 변수로 넘긴다 |
| 터널 토큰 | Terraform이 만들지 않는다. 운영자가 Cloudflare 대시보드에서 복사해 Parameter Store(SecureString)에 넣는다 |

**IMDS 차단**: api와 `cloudflared`는 고정 대역(`172.28.0.0/24`)의 `edge` 네트워크만 쓴다. EC2의 user_data가 설치하는 systemd 단위(`<name_prefix>-block-imds.service`)가 부팅할 때마다 `docker.service`보다 먼저 이 대역에서 `169.254.169.254`로 가는 요청을 막는 규칙을 DOCKER-USER 체인에 넣는다. 재부팅이나 중지 후 시작에도 컨테이너가 뜨기 전에 규칙이 적용되므로 따로 실행할 것이 없다. 규칙을 넣지 못하면 Docker도 시작하지 않으므로, 컨테이너가 모두 멈춰 있으면 `systemctl status <name_prefix>-block-imds.service`부터 확인한다. worker는 EC2 역할이 필요하므로 이 네트워크에 넣지 않는다.

```bash
docker compose -f infra/deploy/compose.yml up -d
```

**apply 후 확인**:

1. EC2에서 `systemctl is-active <name_prefix>-block-imds.service`가 `active`이고 `sudo iptables -S DOCKER-USER`에 `-s 172.28.0.0/24 -d 169.254.169.254/32 -j DROP`이 있다.
2. api 컨테이너에서 `169.254.169.254`에 접근되지 않는다.
3. EC2를 재부팅한 뒤에도 api 컨테이너에서 `169.254.169.254`에 접근되지 않는다.

문법만 확인하려면 값을 임시로 채워 `docker compose -f infra/deploy/compose.yml config`를 실행한다.

### 종료할 때

1. `pg_dump`로 DB를 따로 보관한다. 종료할 때 최종 스냅샷을 만들지 않는다.
2. `rds_deletion_protection = false`, `raw_force_destroy = true`로 바꿔 apply한다.
3. `terraform destroy`를 실행한다.

state 버킷(`bootstrap/`)은 삭제 방지가 걸려 있어 함께 지워지지 않는다.

### 예상 월 비용

서울 리전의 공개 가격으로 추정한 값이다(1달러 = 1,400원 가정). 실제 청구액은 AWS 요금 계산기와 청구서로 확인한다.

| 항목 | 월 비용(USD) |
|---|---|
| EC2 t3.small | 약 19 |
| RDS db.t4g.micro | 약 19 |
| RDS 저장소 gp3 20GB | 약 3 |
| EC2 루트 볼륨 gp3 20GB | 약 2 |
| 공인 IPv4(자동 할당) | 약 4 |
| S3, SQS, Lambda, API Gateway, Parameter Store, SNS, 예산 | 1 미만(트래픽이 적을 때) |
| **합계** | **약 47(약 6.6만 원)** |

프리 티어 적용 여부는 계정의 요금제에 따라 다르므로 apply 전에 콘솔의 Billing에서 확인한다.
