# 0001. 배포 구성: 화면·API 도메인 분리, Cloudflare Tunnel

- 날짜: 2026-11-14
- 작성: neru-33 (팀장)
- 상태: 제안 (PR 리뷰로 확정)
- 관련: #19 (Terraform 기본 자원), #41 (외부 접속 구성), #42 (CORS와 쿠키), T-49 (배포)

도메인은 `eclipse-sync.com`이다. 도메인 이름은 Terraform 변수와 API·프런트엔드의 환경 변수로 받고 코드에 직접 쓰지 않는다.

## 1. 화면과 API를 서로 다른 도메인으로 제공하고, API는 Cloudflare Tunnel로 받는다

**배경**: 연계 프로젝트는 CloudFront 하나가 화면(S3)과 API(EC2)를 같은 도메인으로 제공하고, CloudFront에서 EC2까지는 HTTP(80)로 보낸다. 이 구간으로 세션 쿠키, GitHub 로그인 코드, 운영자 비밀번호가 평문으로 오간다. EC2를 지키려면 CloudFront 대역 제한, 비밀 헤더 검사, `/api` 응답 캐시 끄기가 모두 맞아야 한다.

**결정**

- `www.eclipse-sync.com`: CloudFront가 S3의 React 정적 파일을 제공한다.
- `api.eclipse-sync.com`: Cloudflare Tunnel로 받는다. EC2의 `cloudflared` 컨테이너가 Cloudflare로 먼저 연결해 터널을 만들고, 요청은 이 터널로 들어온다. `cloudflared`는 같은 Docker 네트워크의 api 컨테이너(`http://api:8080`)로 넘긴다.
- EC2는 들어오는 포트를 하나도 열지 않는다. api의 8080도 호스트에 열지 않는다.
- 브라우저에서 CloudFront, 브라우저에서 Cloudflare까지는 HTTPS이고, Cloudflare에서 `cloudflared`까지는 암호화된 터널이다. `cloudflared`에서 api까지는 같은 EC2 안의 컨테이너 사이 통신이다.
- 같은 출처(same origin)는 유지하지 않는다. API는 CORS로 `www` 출처만 허용한다(5절).

```text
브라우저 ──HTTPS──▶ www → CloudFront ──▶ S3 (React 정적 파일)
브라우저 ──HTTPS──▶ api → Cloudflare ══터널══ EC2의 cloudflared ──▶ api 컨테이너:8080 ──▶ RDS:5432
                                         (EC2가 Cloudflare로 먼저 연결)
```

**감수하는 점**

- Cloudflare가 TLS를 풀어서 터널로 다시 보내기 때문에, API 요청 내용(세션 쿠키, 로그인 정보)을 Cloudflare가 처리한다.
- Cloudflare에 장애가 나거나 `cloudflared`가 멈추면 API를 쓸 수 없다. `cloudflared` 컨테이너는 자동으로 다시 시작한다.

**대안**

| 대안 | 장점 | 제외 이유 |
|---|---|---|
| CloudFront 하나로 화면과 API 제공 (`/api/*`를 EC2:80으로) | 같은 출처라 CORS가 필요 없다 | CloudFront에서 EC2까지 평문이다. 대역 제한, 비밀 헤더, 헤더 검사, 캐시 끄기 중 하나만 빠져도 보호가 깨진다. |
| 위 구성에서 CloudFront와 EC2 사이를 HTTPS로 연결 | 같은 출처를 유지하면서 평문 구간이 없다 | 원본 도메인이 공개 DNS와 인증서 발급 기록에 남아 EC2 IP를 숨기지 못한다. CloudFront 대역은 모든 고객이 같이 써서 비밀 헤더도 계속 필요하다. |
| CloudFront VPC origin (EC2를 프라이빗 서브넷에 두고 CloudFront가 직접 연결) | 같은 출처를 유지하고 EC2에 공인 IP가 없다 | EC2가 GitHub API 같은 외부로 나가려면 NAT가 필요하다. 서울 리전 NAT 게이트웨이는 시간 요금만 월 40달러가 넘는다. |
| `api`를 EC2 고정 IP로 바로 연결 (Caddy, Let's Encrypt) | 중간에서 TLS를 푸는 곳이 없다 | 443과 80을 모든 주소에 열어야 한다. HTTP 요청 폭주를 EC2가 그대로 받는데, EC2에는 AWS WAF를 붙일 수 없다. Let's Encrypt 발급 한도(같은 이름 7일 5회)와 갱신 실패 감지도 챙겨야 한다. |
| EC2 앞에 ALB를 두고 ACM 인증서와 AWS WAF | AWS 안에서 인증서와 방화벽을 관리한다 | 서울 리전 기준 ALB만 월 20달러 안팎이 더 든다. `infra/README.md`의 예상 월 비용의 절반 가까이다. |
| Cloudflare 프록시 + 보안 그룹에서 Cloudflare 대역만 허용 + 공용 Authenticated Origin Pulls | 들어오는 포트를 Cloudflare 대역의 443으로 좁힌다 | Cloudflare 대역과 공용 클라이언트 인증서를 모든 Cloudflare 고객이 같이 쓴다. 다른 사람이 자기 Cloudflare 계정에서 EC2 IP를 가리키면 그대로 들어온다. |
| 위 구성에 전용 Authenticated Origin Pulls 인증서와 Cloudflare Origin CA 인증서 | 다른 Cloudflare 계정을 거친 접속도 막는다 | EC2에 443을 열어 두고, 인증서 2개를 해마다 바꾸고(Origin CA는 만료 알림이 없음), Cloudflare 대역 변경을 따로 감시해야 한다. 터널은 같은 보호를 이런 관리 없이 얻는다. |

## 2. 인증서

**배경**: 무료 ACM 공개 인증서는 개인 키를 꺼낼 수 없어 CloudFront 같은 AWS 서비스에만 붙일 수 있다. 터널을 쓰면 EC2가 브라우저나 Cloudflare에 인증서를 보여 줄 일이 없다.

**결정**

| 구간 | 인증서 | 발급과 갱신 |
|---|---|---|
| 브라우저 → CloudFront (`www`) | ACM 공개 인증서, us-east-1 리전(CloudFront 요건) | Terraform이 확인용 CNAME을 Cloudflare에 만들고, ACM이 자동 갱신 |
| 브라우저 → Cloudflare (`api`) | Cloudflare 엣지 인증서 | Cloudflare가 자동 발급·갱신 |
| Cloudflare → `cloudflared` | 없음 (터널 토큰으로 인증하는 암호화 연결) | 관리할 인증서 없음 |

- ACM 인증서는 Terraform의 us-east-1용 provider로 만들고, 확인용 CNAME도 Terraform이 Cloudflare에 만든다.
- EC2에는 인증서를 두지 않는다.

## 3. DNS와 Cloudflare 설정은 Terraform으로 관리한다

**결정**

- 도메인은 Cloudflare에서 구매했고 DNS도 Cloudflare가 맡는다. DNSSEC를 켠다. 무료 플랜을 쓴다.
- 터널, 공개 주소, DNS 레코드, 영역 설정, 요청 제한 규칙은 Terraform의 Cloudflare provider로 만든다. 같은 구성을 언제든 다시 만들 수 있게 하기 위해서다.
- Terraform 밖에서 한 번만 하는 일은 도메인 구매, DNSSEC 켜기, Cloudflare API 토큰 발급이다. 절차는 `infra/README.md`에 적는다.
- Cloudflare API 토큰에는 이 구성에 필요한 권한만 주고, 환경 변수로 Terraform에 넘긴다. 저장소와 tfvars에는 넣지 않는다.

| 이름 | 종류 | 값 | 프록시 |
|---|---|---|---|
| `www` | CNAME | CloudFront 배포 주소(`dxxxx.cloudfront.net`) | 끔 (DNS only) |
| `api` | CNAME | `<터널 ID>.cfargotunnel.com` | 켬 (터널은 프록시가 켜져 있어야 동작한다) |
| ACM 확인용 이름 | CNAME | ACM이 알려 주는 값 | 끔 (DNS only) |

- `www`는 CloudFront 앞에 CDN을 겹치지 않고 ACM 인증서를 그대로 쓰기 위해 프록시를 끈다.
- `cfargotunnel.com` 주소는 같은 Cloudflare 계정의 도메인에서만 동작한다. 다른 계정에서 이 주소를 가리켜도 우리 터널로 들어올 수 없다.

| 설정 | 값 | 이유 |
|---|---|---|
| Always Use HTTPS | 켬 | HTTP 요청을 Cloudflare가 HTTPS로 돌려보낸다 |
| 요청 제한 규칙 (무료 1개) | `POST /auth/admin/login`, IP당 10초에 10회를 넘으면 10초 차단 | 운영자 비밀번호 무차별 대입을 막는다. API의 계정별 실패 제한(`login_attempts`)과 함께 동작한다 |

- GitHub 로그인 경로에는 요청 제한을 걸지 않는다. 비밀번호 확인은 GitHub가 하고, 학교 네트워크처럼 IP 하나를 여럿이 같이 쓰는 곳에서는 정상 사용자가 막힐 수 있다.

## 4. EC2는 들어오는 포트를 열지 않는다

**결정**

| 방향 | 포트 | 대상 | 용도 |
|---|---|---|---|
| 들어옴 | 전체 | 없음 | 터널은 EC2가 먼저 연결한다. 관리 접속은 Session Manager |
| 나감 | 전체 | 0.0.0.0/0 | Cloudflare 터널, GitHub API, AWS 서비스, RDS, 이미지 저장소 |

- EC2의 공인 IP는 밖으로 나갈 때만 쓴다. DNS가 EC2 IP를 가리키지 않으므로 고정 IP(EIP)는 두지 않는다.
- 터널 토큰은 Parameter Store(SecureString)에 두고, 배포 스크립트가 `cloudflared`에 넘긴다. Terraform state에는 남기지 않는다.
- 터널 말고는 api로 들어올 길이 없으므로, api는 `cloudflared`가 넘긴 `CF-Connecting-IP`를 사용자 IP로 쓴다. api의 8080을 호스트에 열지 않는 것이 이 판단의 전제다.
- CloudFront 대역 규칙은 두지 않는다. RDS 보안 그룹은 EC2 보안 그룹에서 오는 5432만 받는다.
- HTTP 요청 폭주는 Cloudflare가 거르고, EC2 공인 IP로 오는 네트워크 공격은 AWS Shield Standard가 자동으로 막는다.

## 5. CORS, 쿠키, CSRF

**결정**

- 허용 출처는 `https://www.eclipse-sync.com` 하나이고 환경 변수로 받는다. 와일드카드(`*.eclipse-sync.com`)를 쓰면 쓰지 않는 하위 도메인이 탈취됐을 때 그 도메인에서 API를 호출할 수 있다.
- 요청의 `Origin`이 허용 출처와 정확히 같을 때만 그 값을 `Access-Control-Allow-Origin`으로 응답하고, `Access-Control-Allow-Credentials: true`와 `Vary: Origin`을 붙인다. 허용 헤더는 `Content-Type`, `X-CSRF-Token`이다.
- 본문이 있는 요청(`Content-Type: application/json`)과 `X-CSRF-Token` 헤더 때문에 쓰기 요청 앞에 사전 요청(OPTIONS)이 간다. 사전 요청은 쿠키 없이 오므로 로그인 검사보다 먼저 CORS 처리에서 응답한다.
- 세션 쿠키는 `api` 호스트 전용이고 `Secure`, `HttpOnly`, `SameSite=Lax`다. `www`와 `api`는 같은 사이트(`eclipse-sync.com`)라 `Lax`로도 쿠키가 실린다.
- CSRF는 공통 인증(#23)의 `X-CSRF-Token` 헤더 방식을 그대로 쓴다.

## 6. 영향 범위

| 대상 | 바꿀 내용 |
|---|---|
| Terraform 기본 자원 (#19) | CloudFront 대역 80 규칙과 `allow_cloudfront_origin` 변수 삭제 |
| 외부 접속 구성 (#41) | Cloudflare provider 추가, 터널·공개 주소·DNS 레코드·영역 설정·요청 제한 규칙, 터널 토큰용 Parameter Store 항목, `cloudflared` 컨테이너, EIP 제거, CloudFront와 웹 버킷, ACM 인증서(us-east-1용 provider), React Router 주소를 새로고침했을 때 `index.html`로 응답하는 설정 |
| 백엔드 API (#42) | CORS 허용 출처를 환경 변수로 받기, 사전 요청 처리, 운영에서 세션 쿠키 `Secure` 켜기, 로그인 후 `www`로 이동, `CF-Connecting-IP`를 사용자 IP로 사용 |
| 프런트엔드 | 배포 빌드에서 `VITE_API_BASE_URL`을 `https://api.eclipse-sync.com`으로 설정. API 주소 환경 변수와 쿠키 포함 요청(`credentials: 'include'`)은 이미 구현되어 있다 |
| GitHub App | Callback URL을 `https://api.eclipse-sync.com/auth/github/callback`으로 설정 |
| 문서 | `docs/github-app.md`의 Callback URL, `infra/README.md`의 Cloudflare API 토큰 발급 절차와 권한, 터널 토큰 넣는 방법 |

Webhook 수신 경로(GitHub → API Gateway → Lambda)는 이 결정의 영향을 받지 않는다.
