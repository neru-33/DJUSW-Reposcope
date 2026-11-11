# 0001. 배포 구성: 화면·API 도메인 분리, 구간별 TLS, Cloudflare DNS

- 날짜: 2026-11-12
- 작성: neru-33 (팀장)
- 상태: 제안 (PR 리뷰로 확정)
- 관련: #19 (Terraform 기본 자원), T-49 (배포)

도메인 이름 `reposcope.example`은 예시다. 실제 도메인을 정하면 바꾼다.

## 1. 화면과 API를 서로 다른 도메인으로 제공한다

**배경**: 연계 프로젝트는 CloudFront 하나가 화면(S3)과 API(EC2)를 같은 도메인으로 제공하고, CloudFront에서 EC2까지는 HTTP(80)로 보낸다. 이 구간으로 세션 쿠키, GitHub 로그인 코드, 운영자 비밀번호가 평문으로 오간다. EC2를 지키려면 CloudFront 대역 제한, 비밀 헤더 검사, `/api` 응답 캐시 끄기가 모두 맞아야 한다.

**결정**

- `www.reposcope.example`: CloudFront가 S3의 React 정적 파일을 제공한다.
- `api.reposcope.example`: EC2 고정 IP로 바로 연결하고, EC2의 Caddy가 443에서 TLS를 처리한 뒤 api 컨테이너로 넘긴다.
- 브라우저에서 CloudFront, 브라우저에서 Caddy까지는 HTTPS다. Caddy에서 api까지는 같은 EC2 안의 컨테이너 사이 통신이다.
- 같은 출처(same origin)는 유지하지 않는다. API는 CORS로 `www` 출처만 허용한다(5절).

```text
브라우저 ──HTTPS──▶ www → CloudFront ──▶ S3 (React 정적 파일)
브라우저 ──HTTPS──▶ api → EC2:443 Caddy ──▶ api 컨테이너:8080 ──▶ RDS:5432
브라우저 ──이름 조회──▶ Cloudflare DNS
```

**대안**

| 대안 | 장점 | 제외 이유 |
|---|---|---|
| CloudFront 하나로 화면과 API 제공 (`/api/*`를 EC2:80으로) | 같은 출처라 CORS가 필요 없다 | CloudFront에서 EC2까지 평문이다. 대역 제한, 비밀 헤더, 헤더 검사, 캐시 끄기 중 하나만 빠져도 보호가 깨진다. |
| 위 구성에서 CloudFront와 EC2 사이를 HTTPS로 연결 | 같은 출처를 유지하면서 평문 구간이 없다 | EC2에도 공개 인증서가 필요해 인증서가 둘이 되고, 비밀 헤더도 계속 필요하다. 세 방식 중 가장 복잡하다. |
| CloudFront VPC origin (EC2를 프라이빗 서브넷에 두고 CloudFront가 직접 연결) | 같은 출처를 유지하고 EC2가 인터넷에 노출되지 않는다 | EC2가 GitHub API 같은 외부로 나가려면 NAT가 필요하다. |

## 2. 인증서는 `www`에 ACM, `api`에 Let's Encrypt를 쓴다

**배경**: 무료 ACM 공개 인증서는 개인 키를 꺼낼 수 없어 CloudFront, 로드 밸런서 같은 AWS 서비스에만 붙일 수 있다. EC2 안의 Caddy에는 설치할 수 없다.

**결정**

| 도메인 | 인증서 | 발급과 갱신 |
|---|---|---|
| `www` | ACM 공개 인증서, us-east-1 리전(CloudFront 요건) | 확인용 CNAME을 Cloudflare에 등록하면 ACM이 자동 갱신 |
| `api` | Let's Encrypt | Caddy가 HTTP-01 방식(80번)으로 자동 발급·갱신 |

- ACM 인증서는 Terraform(`aws_acm_certificate`)으로 만들고, 확인용 CNAME 값은 output으로 꺼낸다.
- Let's Encrypt 계정 연락처 이메일은 배포 설정의 환경 변수로 넣고 저장소에는 적지 않는다.
- Let's Encrypt는 만료 알림 메일을 보내지 않는다. 갱신 실패는 이메일이 아닌 다른 방법으로 확인해야 한다(8절).

**대안**

| 대안 | 장점 | 제외 이유 |
|---|---|---|
| EC2 앞에 ALB를 두고 ALB에 ACM 인증서 | `api` 인증서도 AWS가 관리한다 | 서울 리전 기준 월 20달러 안팎이 더 든다. 예상 월 비용(약 45달러)의 절반 가까이다. |
| 내보낼 수 있는 유료 ACM 인증서를 EC2에 설치 | `api` 인증서도 AWS에서 발급한다 | 유료이고, 갱신될 때마다 EC2에 다시 넣어야 한다. |

## 3. DNS는 Cloudflare에서 관리한다

**결정**

- 도메인은 Cloudflare에서 구매한다. Cloudflare에서 산 도메인은 DNS도 Cloudflare가 맡아 네임서버를 따로 바꾸지 않는다. 무료 플랜을 쓴다.
- 레코드는 모두 프록시를 끈다(DNS only). 프록시를 켜면 Cloudflare가 중간에서 TLS를 풀었다가 다시 거는 구조가 된다.
- Cloudflare 레코드는 AWS 자원이 아니므로 Terraform 밖에서 관리한다.

| 이름 | 종류 | 값 |
|---|---|---|
| `www` | CNAME | CloudFront 배포 주소(`dxxxx.cloudfront.net`) |
| `api` | A | EC2 고정 IP(`terraform output app_public_ip`) |
| ACM 확인용 이름 | CNAME | ACM이 알려 주는 값 |

## 4. EC2는 443과 80만 연다

**결정**

| 방향 | 포트 | 대상 | 용도 |
|---|---|---|---|
| 들어옴 | 443 | 0.0.0.0/0 | API HTTPS |
| 들어옴 | 80 | 0.0.0.0/0 | Let's Encrypt 확인, HTTPS로 이동 |
| 들어옴 | 22 | 없음 | 관리 접속은 Session Manager |
| 나감 | 전체 | 0.0.0.0/0 | GitHub API, AWS 서비스, RDS, 이미지 저장소 |

- 443과 80은 Caddy가 실제로 뜨는 배포 작업에서 연다.
- CloudFront 대역에서 오는 80 규칙은 두지 않는다. CloudFront가 EC2를 거치지 않기 때문이다.
- RDS 보안 그룹은 EC2 보안 그룹에서 오는 5432만 받는다.

## 5. CORS, 쿠키, CSRF

**결정**

- 허용 출처는 `https://www.reposcope.example` 하나다. 와일드카드(`*.reposcope.example`)를 쓰면 쓰지 않는 하위 도메인이 탈취됐을 때 그 도메인에서 API를 호출할 수 있다.
- 쿠키를 보내는 요청이므로 API는 `Access-Control-Allow-Credentials: true`와 요청 출처를 그대로 응답한다. 허용 헤더에 `X-CSRF-Token`을 넣는다.
- 세션 쿠키는 `api` 호스트 전용이고 `Secure`, `HttpOnly`, `SameSite=Lax`다. `www`와 `api`는 같은 사이트라 `Lax`로도 쿠키가 실린다.
- CSRF는 공통 인증(#23)의 `X-CSRF-Token` 헤더 방식을 그대로 쓴다.

## 6. EC2 역할은 worker 컨테이너만 쓴다

**배경**: EC2에는 IAM 역할이 하나뿐이고, 컨테이너가 이 역할을 받을 수 있게 IMDS hop limit을 2로 둔다. 이대로면 EC2의 모든 컨테이너가 GitHub App 키 읽기, SQS 메시지 삭제, 원본 이벤트 읽기 권한을 갖는다.

**결정**

- api 컨테이너와 Caddy 컨테이너는 IMDS(`169.254.169.254`) 접근을 막는다. 연계 프로젝트의 결정 0002와 같은 방식이다.
- api에 필요한 비밀값(DB 비밀번호, GitHub Client secret)은 배포 스크립트가 Parameter Store에서 읽어 환경 변수로 넣는다.

## 7. 영향 범위

| 대상 | 바꿀 내용 |
|---|---|
| Terraform 기본 자원 (#19) | CloudFront 대역 80 규칙과 `allow_cloudfront_origin` 변수 삭제, EC2 설정 주석에 IMDS 차단 요건 기록 |
| 배포 작업 (T-49) | 보안 그룹 443·80 추가, Caddy와 Let's Encrypt 인증서, IMDS 차단, CloudFront와 웹 버킷, ACM 인증서 |
| 백엔드 API | CORS 허용 출처를 환경 변수로 받기, 운영에서 세션 쿠키 `Secure` 켜기, 로그인 후 `www`로 이동 |
| 프런트엔드 | API 주소를 환경 변수로 받기, 요청에 쿠키 포함(`credentials: 'include'`) |
| GitHub App | Callback URL을 `https://api.reposcope.example/auth/github/callback`으로 설정 |
| 문서 | `docs/github-app.md`의 Callback URL, `infra/README.md`의 DNS와 인증서 절차 |

Webhook 수신 경로(GitHub → API Gateway → Lambda)는 이 결정의 영향을 받지 않는다.

## 8. 정해지지 않은 것

- [ ] 실제 도메인 이름 (Cloudflare에서 구매)
- [ ] Let's Encrypt 계정 연락처로 쓸 팀 공용 이메일
- [ ] Let's Encrypt 인증서 갱신 실패를 알아챌 방법
