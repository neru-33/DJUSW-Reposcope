# GitHub App 설정

RepoScope가 사용하는 GitHub App의 설정을 기록한다. 비밀값은 이 문서와 저장소에 두지 않는다.

## 기본 정보

| 항목 | 값 |
|---|---|
| 이름 | reposcope-djusw |
| 소유 계정 | surhwa11228 |
| App ID | 5666890 |
| Client ID | Iv23liDO7KsFD2DHGFwd |
| 공개 상태 | 공개 (Any account) |

## 권한

Repository permissions만 사용하고 모두 읽기 전용이다. Organization permissions와 Account permissions는 모두 No access다.

| 권한 | 값 | 용도 |
|---|---|---|
| Metadata | Read-only | GitHub App 필수 권한 |
| Contents | Read-only | 커밋 조회 |
| Pull requests | Read-only | PR과 리뷰 조회 |
| Issues | Read-only | 이슈 조회 |

권한을 늘리면 설치한 모든 사용자가 다시 승인해야 한다.

## 구독 이벤트

| 이벤트 | 용도 |
|---|---|
| push | 커밋 수집 |
| pull_request | PR 수집 |
| pull_request_review | 리뷰 수집 |
| issues | 이슈 수집 |

`installation`과 `installation_repositories`는 구독하지 않아도 자동으로 수신한다. 이벤트를 구독하려면 Webhook을 켜고 URL을 지정해야 한다.

## 사용자 인증과 URL

| 항목 | 값 |
|---|---|
| Expire user authorization tokens | 사용 |
| Request user authorization during installation | 사용 안 함 |
| Device Flow | 사용 안 함 |
| Callback URL | `https://api.eclipse-sync.com/auth/github/callback` (api는 Cloudflare Tunnel로 받는다. [결정 기록](decisions/0001-deploy-domain-tls.md)) |
| Setup URL | 비워 둠 |

## Webhook

| 항목 | 값 |
|---|---|
| Webhook | 사용 |
| Webhook URL | API Gateway 주소(`infra/envs/prod`의 `terraform output webhook_url`) |
| Webhook secret | Parameter Store `/reposcope/webhook-secret`과 같은 값 |

수신 Lambda는 `X-Hub-Signature-256` 서명을 검사한다. 서명이 맞으면 2xx, 틀리면 401로 응답한다. Parameter Store의 secret이 초기값(`CHANGE_ME`)이거나 32자 미만이면 서명을 검사하지 않고 503으로 응답한다. 응답 코드는 App 설정의 Advanced → Recent Deliveries에서 확인한다.

Webhook secret을 바꿀 때는 아래 순서를 따른다.

1. 32자 이상의 임의의 secret을 만들어 Parameter Store의 `/reposcope/webhook-secret`에 저장한다.
2. App 설정의 Webhook secret에 같은 값을 입력한다.
3. Lambda는 secret을 최대 5분 동안 캐시하므로 그동안의 전송은 401일 수 있다. 5분 뒤 Recent Deliveries에서 401인 전송을 Redeliver하고 2xx인지 확인한다.

앱은 공개 상태이고, 다른 계정에 설치된 뒤에는 비공개로 되돌릴 수 없다.

## 비밀값

| 값 | 보관 위치 |
|---|---|
| Private key | Parameter Store `/reposcope/github-app-key` |
| Client secret | Parameter Store `/reposcope/github-client-secret` |
| Webhook secret | Parameter Store `/reposcope/webhook-secret` |

Private key와 Client secret은 만들어 위 위치에 저장했다. 키 파일은 저장 직후 로컬에서 삭제한다. 유출이 의심되면 GitHub에서 해당 값을 새로 만들고 이전 값을 삭제한 뒤 Parameter Store를 교체하고 팀에 알린다.
