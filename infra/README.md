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
| `modules/cloudflare` | 터널, api 공개 주소와 DNS, Always Use HTTPS, 요청 제한 |
| `modules/web` | 웹 버킷(S3), CloudFront, ACM 인증서(us-east-1), `www`와 인증서 확인용 DNS |
| `modules/ecr` | api, worker 이미지 저장소와 오래된 이미지 정리 규칙 |
| `modules/github-oidc` | GitHub Actions가 AWS에 들어오는 OIDC 역할 |
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

### Cloudflare 준비 (Terraform 밖)

`api.eclipse-sync.com`은 Cloudflare Tunnel로 받는다([결정 기록](../docs/decisions/0001-deploy-domain-tls.md)). 아래는 Terraform이 하지 않고 사람이 한 번 하는 일이다.

1. 도메인(`eclipse-sync.com`)을 Cloudflare에 추가하고(Free 플랜) 도메인을 산 곳에서 네임서버를 Cloudflare가 알려 준 두 개로 바꾼다. 상태가 **Active**가 될 때까지 기다린다.
2. 도메인 Overview의 **Zone ID**와 **Account ID**를 `terraform.tfvars`에 넣는다(`cloudflare_zone_id`, `cloudflare_account_id`).
3. **DNSSEC**을 켠다. DNS → Settings → DNSSEC에서 켜고, 도메인을 산 곳에 화면의 DS 레코드를 등록한다. 네임서버 변경이 끝난 뒤에 한다. Cloudflare Registrar에서 샀다면 DS 레코드가 자동 등록된다.
4. **API 토큰**을 발급한다. My Profile → API Tokens → Create Custom Token에서 아래 권한만 주고, 이 도메인과 이 계정으로 제한한다. Global API Key는 쓰지 않는다.

   | 범위 | 항목 | 권한 |
   |---|---|---|
   | Zone | DNS | Edit |
   | Zone | Zone Settings | Edit |
   | Zone | Zone WAF | Edit |
   | Account | Cloudflare Tunnel | Edit |

5. 토큰은 환경 변수로만 넘긴다. 파일과 tfvars에 쓰지 않는다. `plan`, `apply`, `destroy`를 실행할 때마다 필요하므로 새 터미널에서는 다시 설정한다.

   ```powershell
   $env:CLOUDFLARE_API_TOKEN = "<토큰>"
   ```

   ```bash
   export CLOUDFLARE_API_TOKEN="<토큰>"
   ```

### 처음 만들 때

AWS 계정이 무료(Free) 플랜이면 RDS 자동 백업 보존 기간(7일)이 허용되지 않아 apply가 `FreeTierRestrictionError`로 멈춘다. 아래 결과의 `accountPlanType`이 `FREE`이면 apply 전에 [유료 플랜으로 올린다](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html). 유료 플랜은 무료 플랜으로 되돌릴 수 없다.

```bash
aws freetier get-account-plan-state --region us-east-1
```

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

3. `terraform.tfvars.example`을 `terraform.tfvars`로 복사해 `alert_email`, `cloudflare_zone_id`, `cloudflare_account_id`를 채운다.
4. RDS 비밀번호를 환경 변수로 넘긴다. Parameter Store의 `/reposcope/db-password`에 넣을 값과 같아야 하며 16자 이상이다. `plan`, `apply`, `destroy`를 실행할 때마다 필요하므로, 새 터미널에서는 다시 설정한다.

   ```powershell
   $env:TF_VAR_db_password = "<16자 이상 비밀번호>"
   ```

5. `terraform plan`으로 결과를 확인한 뒤 `terraform apply`를 실행한다.
6. 비밀값을 Parameter Store에 넣는다. Terraform은 값을 읽지도 바꾸지도 않는다.

   `operator-otp-key`에는 세션과 별도로 생성한 32바이트 임의 키의 Base64 값을 넣는다. 이 키는 API 인스턴스와 재배포 간에 유지하며, 변경하거나 잃으면 기존 OTP 비밀키를 복호화할 수 없다. 비밀값이 셸 기록이나 로그에 남지 않도록 안전하게 입력한다.

   `openssl rand -base64 32`로 키를 생성할 수 있다. 출력은 비밀값이므로 안전한 환경에서 생성해 Parameter Store에 입력하고 터미널 기록이나 로그에 남기지 않는다. 설정된 키가 올바른 Base64가 아니거나 디코딩 결과가 32바이트가 아니면 API는 시작하지 않는다.

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

### 터널 토큰 넣기

터널 토큰은 Terraform이 만들지도 읽지도 않아 state에 남지 않는다. apply가 끝난 뒤 운영자가 직접 넣는다.

1. `terraform output tunnel_id`로 터널 ID를 확인한다.
2. Cloudflare 대시보드 → Networking → Tunnels에서 그 터널을 열고, Overview 탭의 **Add a replica**에 나오는 설치 명령에서 `eyJ`로 시작하는 문자열(토큰)을 복사한다. 명령은 실행하지 않는다.
3. Parameter Store에 넣는다. 배포 스크립트가 이 값을 읽어 `cloudflared`에 `TUNNEL_TOKEN`으로 넘긴다.

   ```bash
   aws ssm put-parameter --name /reposcope/tunnel-token --type SecureString --overwrite --value "<토큰>"
   ```

4. apply 뒤 state에 터널 비밀값이 남지 않았는지 확인한다. state에는 `tunnel_secret`이라는 항목이 있지만 값이 `null`이어야 한다(값이 `null`이 아니면 비밀값이 남은 것이다). 값을 화면에 출력하지 않으려면 `null`인 줄이 있는지만 센다.

   ```bash
   terraform state pull | grep -c '"tunnel_secret": null'
   ```

   ```powershell
   (terraform state pull | Select-String -Pattern '"tunnel_secret": null').Count
   ```

   결과가 `1`이면 정상이고 `0`이면 비밀값이 남은 것이다(비밀값을 공유하지 말고 아래 절차로 간다).

   비밀값이 남으면 Terraform으로 터널을 만들지 않는다. 대시보드에서 터널만 만들고 그 ID를 `terraform.tfvars`의 `cloudflare_tunnel_id`에 넣은 뒤 다시 apply한다. 공개 주소와 DNS는 그대로 Terraform이 만든다.

토큰을 바꾸면(재발급) 같은 명령으로 다시 넣고 EC2에서 `cloudflared`를 다시 시작한다.

### 화면(www) 확인

`modules/web`은 웹 버킷과 CloudFront, 인증서, `www` 레코드까지만 만든다. 화면 파일 업로드와 캐시 무효화는 배포 자동화(#75)에서 하므로, apply 직후에는 버킷이 비어 있어 `www`가 오류를 돌려주는 것이 정상이다. 처음 확인할 때는 빌드를 직접 올린다.

```bash
cd frontend && npm ci && npm run build
aws s3 sync dist "s3://$(terraform -chdir=../infra/envs/prod output -raw web_bucket_name)" --delete
aws cloudfront create-invalidation --distribution-id "$(terraform -chdir=../infra/envs/prod output -raw web_distribution_id)" --paths "/*"
```

apply 뒤 아래를 확인한다.

| 확인 | 기대 결과 |
|---|---|
| `curl -I https://www.eclipse-sync.com` | `200`, `content-type: text/html` |
| `curl -I http://www.eclipse-sync.com` | `301`로 HTTPS로 이동 |
| `curl -I https://www.eclipse-sync.com/<없는 경로>` | `200`(`index.html`). 브라우저에서 화면 안의 주소를 새로고침해도 화면이 열린다 |
| 웹 버킷 주소로 직접 접근 | `403`(버킷은 CloudFront만 읽는다) |

CloudFront 배포는 만드는 데 5분에서 15분이 걸린다. 인증서 확인용 레코드는 Cloudflare에 만들어지므로 같은 터미널에 `CLOUDFLARE_API_TOKEN`이 설정되어 있어야 한다.

### 이미지 저장소와 배포 권한

배포 자동화(#75)가 쓰는 자원이다. 워크플로는 아래 "자동 배포"에서 설명한다.

| 자원 | 내용 |
|---|---|
| ECR `<접두어>-api`, `<접두어>-worker` | 태그를 덮어쓸 수 없다(`IMMUTABLE`). 같은 태그는 항상 같은 이미지이므로 이전 태그로 다시 배포하면 되돌릴 수 있다. 최근 10개만 남기고 지우며 올릴 때 취약점 검사를 한다 |
| EC2 역할 | 이미지를 받을 수만 있다. 올리는 권한은 없다 |
| `<접두어>-github-deploy` 역할 | GitHub Actions가 OIDC로 맡는다. 오래 쓰는 접근 키를 저장소 secret에 넣지 않는다 |

배포 역할은 `github_repository`(기본 `neru-33/DJUSW-Reposcope`)의 **`main` 브랜치에서 실행된 워크플로만** 맡을 수 있다. 다른 저장소, 다른 브랜치, 포크의 풀 리퀘스트는 맡지 못한다. 권한은 ECR 이미지 올리기, 이 EC2 한 대에 배포 명령 보내기와 결과 읽기, 웹 버킷에 화면 올리기(읽기 권한 없음), 이 CloudFront 배포의 캐시 무효화로 제한한다.

- 역할의 조건은 토큰의 `sub` 값 `repo:<소유자>@<소유자 ID>/<이름>@<저장소 ID>:ref:refs/heads/main`과 정확히 같아야 한다. 2026-07-15 이후 만든 저장소는 이 형식(소유자 ID와 저장소 ID 포함)을 쓴다. 값이 틀리면 Actions가 `Not authorized to perform sts:AssumeRoleWithWebIdentity`로 멈춘다.
- 저장소 이름이 바뀌거나 조직으로 옮기면 `github_repository`, `github_owner_id`, `github_repository_id`를 확인해 다시 apply한다. ID는 `gh api repos/<소유자>/<이름> --jq '.owner.id, .id'`로 확인한다.
- GitHub OIDC provider는 계정에 하나만 만들 수 있다. 이미 있으면 `terraform.tfvars`에 `github_oidc_provider_arn`을 넣는다.
- 워크플로에서 `environment`를 쓰면 토큰의 `sub` 값이 달라지므로 역할의 조건도 함께 바꾼다.
- 인프라를 종료할 때는 `ecr_force_delete = true`로 바꿔 apply한다.

### 자동 배포(GitHub Actions)

`.github/workflows/deploy.yml`이 `main`에 병합될 때(`backend/api`, `backend/worker`, `backend/migrations`, `frontend`, `infra/deploy`가 바뀐 경우) 다음을 순서대로 한다. 앞 단계가 실패하면 뒤 단계는 하지 않는다.

| 단계 | 내용 |
|---|---|
| 이미지 | api, worker 이미지를 만들어 ECR에 올린다. 태그는 `sha-<커밋 앞 12자>`다. 태그는 덮어쓸 수 없으므로 이미 있으면 다시 만들지 않는다 |
| 배포 | `compose.yml`과 `deploy.sh`를 SSM Run Command 명령에 담아 EC2로 보내 실행한다. 스크립트가 끝날 때까지 기다리고, 실패하면 이 단계도 실패한다. 끝나면 `https://api.eclipse-sync.com/api/version`이 새 태그를 돌려줄 때까지 기다린다 |
| 화면 | api가 새 버전으로 올라온 뒤 화면을 빌드해(`VITE_API_BASE_URL=https://api.eclipse-sync.com`) 웹 버킷에 올리고 CloudFront 캐시를 무효화한다. 해시가 붙은 파일은 오래 캐시하고 `index.html`은 `no-cache`다. 새 파일 → `index.html` → 이전 파일 삭제 순서로 올려 중간에 빈 화면이 보이지 않게 한다 |

AWS 자격 증명은 OIDC로 받는 임시 자격 증명뿐이다. 이 역할은 이 저장소의 `main`에서 실행된 워크플로만 맡을 수 있으므로 다른 브랜치에서 `workflow_dispatch`로 실행하면 역할을 맡지 못한다. 계정 ID는 로그에서 가려진다.

**처음 한 번 설정**(`terraform apply` 뒤). 저장소 관리자 권한이 필요하다. 값은 Terraform 출력에서 가져오고 비밀값은 없다.

```bash
cd infra/envs/prod
gh secret set AWS_DEPLOY_ROLE_ARN --body "$(terraform output -raw deploy_role_arn)"
gh variable set INSTANCE_ID --body "$(terraform output -raw app_instance_id)"
gh variable set DB_HOST --body "$(terraform output -raw db_address)"
gh variable set WEB_BUCKET --body "$(terraform output -raw web_bucket_name)"
gh variable set CLOUDFRONT_DISTRIBUTION_ID --body "$(terraform output -raw web_distribution_id)"
gh variable set GH_APP_ID --body "<GitHub App ID>"
gh variable set GH_CLIENT_ID --body "<GitHub App Client ID>"
```

변수 이름은 `GITHUB_`로 시작할 수 없어 앱 ID와 Client ID는 `GH_APP_ID`, `GH_CLIENT_ID`로 둔다. 인스턴스가 교체되면(`terraform apply`로 EC2가 새로 만들어지면) `INSTANCE_ID`를 다시 넣는다.

**되돌리기**: Actions → deploy → Run workflow에서 `image_tag`에 이전 태그를 넣는다. 이미지와 화면은 새로 만들지 않고 그 태그로 EC2만 다시 배포한다. 화면은 되돌리지 않으므로, 화면도 되돌려야 하면 그 커밋을 다시 `main`에 반영한다. 태그는 ECR에서 확인한다.

```bash
aws ecr describe-images --repository-name reposcope-api --query 'sort_by(imageDetails,&imagePushedAt)[].imageTags' --output text
```

**주의**
- 수신 Lambda(`webhook/`)는 이 워크플로가 배포하지 않는다. 코드가 바뀌면 `terraform apply`로 배포한다.
- 저장소 규칙이 CI(`ci.yml`) 통과를 병합 조건으로 요구한다. 이 워크플로는 테스트를 다시 실행하지 않는다.
- 첫 배포는 EC2에 이미지가 없으므로 이미지 빌드와 pull 시간이 걸린다(스크립트 대기 시간 기본 180초와 별개로 워크플로는 최대 15분 기다리고, EC2의 스크립트 실행 시간 제한(`executionTimeout`)도 15분이다).

### 컨테이너와 EC2 역할

EC2 역할에는 GitHub App 키를 읽는 권한이 있다. 인스턴스 메타데이터의 hop limit이 2여서 EC2 위의 컨테이너는 이 역할의 자격 증명을 받을 수 있다. 이 설정은 worker 컨테이너용이다.

- api와 cloudflared 컨테이너는 배포 설정에서 IMDS 접근을 막는다.
- api가 쓰는 비밀값(DB 비밀번호, GitHub Client secret)은 컨테이너가 직접 읽지 않는다. 배포 스크립트가 Parameter Store에서 읽어 환경 변수로 넘긴다.

### 운영 배포 설정

`infra/deploy/compose.yml`이 EC2에서 worker, api, `cloudflared`를 실행하고, 같은 폴더의 `deploy.sh`가 비밀값을 읽어 이 파일을 실행한다. GitHub Actions로 자동 배포하는 워크플로는 아래 "자동 배포"에서 설명한다.

| 항목 | 내용 |
|---|---|
| worker | `WORKER_IMAGE`로 받은 이미지. 시작할 때 수집·계정 마이그레이션을 적용한다. EC2 역할의 자격 증명으로 SQS와 S3에 접근하므로 `edge` 네트워크에 넣지 않는다 |
| api | `API_IMAGE`로 받은 이미지. 8080은 호스트에 열지 않고 `expose`만 한다 |
| cloudflared | Cloudflare로 먼저 연결해 터널을 만든다. 요청은 `http://api:8080`으로 넘긴다. 자동으로 다시 시작한다 |
| api 환경 변수 | `SESSION_COOKIE_SECURE=true`, `CORS_ALLOWED_ORIGIN`과 `FRONTEND_URL`은 `https://www.eclipse-sync.com`. `GITHUB_CLIENT_ID`와 `GITHUB_CALLBACK_URL`은 GitHub App 사용자 인증에 쓴다 |
| 비밀값 | `DB_PASSWORD`, `TUNNEL_TOKEN`, `GITHUB_CLIENT_SECRET`, `OPERATOR_OTP_KEY`는 파일에 쓰지 않는다. 배포 스크립트가 Parameter Store에서 읽어 환경 변수로 넘긴다. GitHub App 키(`github-app-key`)는 `/etc/<name_prefix>/secrets/github-app-key`(root 소유, worker만 읽는 권한)에 파일로 두고 worker에 읽기 전용으로 붙인다 |
| 터널 토큰 | Terraform이 만들지 않는다. 운영자가 Cloudflare 대시보드에서 복사해 Parameter Store(SecureString)에 넣는다 |

**IMDS 차단**: api와 `cloudflared`는 고정 대역(`172.28.0.0/24`)의 `edge` 네트워크만 쓴다. EC2의 user_data가 설치하는 systemd 단위(`<name_prefix>-block-imds.service`)가 부팅할 때마다 `docker.service`보다 먼저 이 대역에서 `169.254.169.254`로 가는 요청을 막는 규칙을 DOCKER-USER 체인에 넣는다. 재부팅이나 중지 후 시작에도 컨테이너가 뜨기 전에 규칙이 적용되므로 따로 실행할 것이 없다. 규칙을 넣지 못하면 Docker도 시작하지 않으므로, 컨테이너가 모두 멈춰 있으면 `systemctl status <name_prefix>-block-imds.service`부터 확인한다. worker는 EC2 역할이 필요하므로 이 네트워크에 넣지 않는다.

#### 배포 스크립트(`deploy.sh`)

EC2에서 root로 실행한다. `compose.yml`과 같은 폴더에 있어야 한다.

```bash
sudo DB_HOST=<RDS 주소> GITHUB_APP_ID=<앱 ID> GITHUB_CLIENT_ID=<Client ID> ./deploy.sh <이미지 태그>
```

| 입력 | 내용 |
|---|---|
| 이미지 태그 | 필수. `latest`는 쓰지 않는다. api와 worker가 같은 태그를 쓰고 `APP_VERSION`(`/api/version`)도 이 값이다 |
| `DB_HOST` | 필수. `terraform output db_address` 값 |
| `GITHUB_APP_ID` | 선택. 비우면 worker의 GitHub 연동만 꺼진다 |
| `BACKFILL` | 선택. 비우면 켜짐. `off`이면 worker가 과거 기록과 README 재시도를 하지 않는다 |
| `BACKFILL_DAYS` | 선택. 비우면 전체 기록. 1~3650이면 그 기간(일)의 활동만 가져온다 |
| `GITHUB_CLIENT_ID` | 필수. 학생 로그인에 사용할 GitHub App의 Client ID |
| `NAME_PREFIX` | 선택. 기본 `reposcope`. DB 이름·사용자, SQS 큐, 원본 버킷, ECR 저장소, Parameter Store 경로를 만드는 데 쓴다 |
| `DB_NAME`, `DB_USER`, `QUEUE_URL`, `RAW_BUCKET`, `AWS_REGION` | 선택. 비우면 `NAME_PREFIX`, 계정 ID, 인스턴스 리전에서 만든다 |

순서는 다음과 같고, 한 단계라도 실패하면 멈추고 종료 코드 1을 돌려준다(Actions가 이 결과를 기다린다).

1. `<name_prefix>-block-imds.service`가 `active`이고 DOCKER-USER에 차단 규칙이 있는지 확인한다. 없으면 컨테이너를 띄우지 않는다.
2. Parameter Store에서 `db-password`, `tunnel-token`, `github-app-key`, `github-client-secret`, `operator-otp-key`를 읽는다. 값이 비었거나 초기값 `CHANGE_ME`이면 멈춘다. 값은 화면에 출력하지 않고 명령 인자로도 넘기지 않는다.
3. ECR에 로그인해 이미지를 받는다. 로그인 정보는 임시 폴더에 두고 끝나면 지운다.
4. worker를 다시 만들어 시작하고, 마이그레이션이 끝나 로그에 `worker_started`가 나올 때까지 기다린다(기본 180초).
5. api와 `cloudflared`를 시작하고 api의 `Started` 로그를 기다린다. 터널 연결(`Registered tunnel connection`)이 60초 안에 확인되지 않으면 경고만 남긴다. 공개 주소 확인은 호출한 쪽에서 `https://api.eclipse-sync.com/api/version`으로 한다.
6. 만든 지 7일이 지났고 지금 컨테이너가 쓰지 않는 이미지를 정리한다.

**이전 버전으로 되돌리기**: 이전 이미지 태그로 같은 명령을 다시 실행한다. 태그는 덮어쓸 수 없으므로 같은 태그는 항상 같은 이미지다. ECR에는 최근 10개가 남는다.

이 스크립트를 처음 쓸 때는 EC2에 `compose.yml`과 `deploy.sh`를 같은 폴더에 두고 위 명령으로 한 번 실행한다. 배포 워크플로는 이 두 파일을 명령에 담아 EC2에 올리고 실행한다.

EC2가 재부팅되면 `restart: unless-stopped`로 컨테이너가 다시 뜬다. 이때 worker와 api는 동시에 시작하므로, 마이그레이션이 필요한 새 버전은 재부팅이 아니라 `deploy.sh`로 배포한다.

#### 최초 최고관리자 생성

자동 배포는 초기 관리자 ID와 비밀번호를 전달하지 않는다. 최초 한 번만 신뢰할 수 있는 대화형 EC2 root 셸에서 아래 절차를 실행한다. 수집·계정 마이그레이션과 API 마이그레이션을 적용하고 `operator-otp-key`를 설정해야 한다. 동시에 자동 배포가 실행되지 않도록 한다. 명령이나 로그에 비밀값을 직접 적지 않는다.

1. `compose.yml`과 `deploy.sh`가 있는 폴더에서 root 셸을 열고, 위 배포 명령에 필요한 `DB_HOST`, `GITHUB_CLIENT_ID`와 사용하는 선택 설정을 `export`로 환경 변수에 지정한다. 배포할 이미지 태그는 이미 배포된 태그를 쓴다.
2. 셸에서 다음 명령으로 초기 ID와 임시 비밀번호를 입력하고 내보낸다. 입력은 화면에 표시되지 않는다.

   ```bash
   read -r -s -p '초기 관리자 ID: ' OPERATOR_BOOTSTRAP_USERNAME; echo
   read -r -s -p '초기 관리자 비밀번호: ' OPERATOR_BOOTSTRAP_PASSWORD; echo
   export OPERATOR_BOOTSTRAP_USERNAME OPERATOR_BOOTSTRAP_PASSWORD
   ./deploy.sh <이미지 태그>
   ```

3. 배포가 실패한 경우에도 즉시 다음 명령으로 셸에서 초기값을 지운다. API 컨테이너가 생성됐다면 컨테이너 환경에도 초기값이 남지 않도록 초기값 없이 다시 배포하고 완료를 확인한다. 실패 원인을 확인할 때 비밀값을 출력하지 않는다.

   ```bash
   unset OPERATOR_BOOTSTRAP_USERNAME OPERATOR_BOOTSTRAP_PASSWORD
   ./deploy.sh <이미지 태그>
   exit
   ```

4. 최초 관리자 ID와 임시 비밀번호로 로그인해 72시간 안에 ID·비밀번호를 바꾸고 OTP를 등록한다. API 요청 형식은 `docs/api-spec.md` 4장을 따른다. 초기화 기록은 계정 삭제 후에도 남으며 초기 설정값을 다시 지정해도 계정은 재생성되지 않는다.

**apply 후 확인**:

1. EC2에서 `systemctl is-active <name_prefix>-block-imds.service`가 `active`이고 `sudo iptables -S DOCKER-USER`에 `-s 172.28.0.0/24 -d 169.254.169.254/32 -j DROP`이 있다.
2. api 컨테이너에서 `169.254.169.254`에 접근되지 않는다. api 이미지에는 `curl`이 없을 수 있으므로, 컨테이너 안에서 사용할 수 있는 도구로 `169.254.169.254` 연결을 시도해 응답이 없는지 확인한다.
3. EC2를 재부팅한 뒤에도 api 컨테이너에서 `169.254.169.254`에 접근되지 않는다.

문법만 확인하려면 값을 임시로 채워 `docker compose -f infra/deploy/compose.yml config`를 실행한다.

### 종료할 때

1. `pg_dump`로 DB를 따로 보관한다. 종료할 때 최종 스냅샷을 만들지 않는다.
2. `rds_deletion_protection = false`, `raw_force_destroy = true`, `web_force_destroy = true`, `ecr_force_delete = true`로 바꿔 apply한다.
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
| S3, SQS, Lambda, API Gateway, Parameter Store, SNS, 예산, CloudFront, ACM | 1 미만(트래픽이 적을 때) |
| **합계** | **약 47(약 6.6만 원)** |

프리 티어 적용 여부는 계정의 요금제에 따라 다르므로 apply 전에 콘솔의 Billing에서 확인한다.
