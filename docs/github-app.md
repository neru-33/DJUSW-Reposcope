# GitHub App 설정

RepoScope가 사용하는 GitHub App의 설정을 기록한다. 비밀값은 이 문서와 저장소에 두지 않는다.

## 기본 정보

| 항목 | 값 |
|---|---|
| 이름 | reposcope |
| 소유 계정 | surhwa11228 |
| App ID | 5666890 |
| Client ID | Iv23liDO7KsFD2DHGFwd |
| 공개 상태 | 비공개 (Webhook 주소가 정해진 뒤 공개로 전환) |

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
| Callback URL | 경로는 `/auth/github/callback`, 도메인은 배포 주소가 정해진 뒤 설정 |
| Setup URL | 배포 주소가 정해진 뒤 설정 |

## Webhook

Webhook은 켜 두었고, URL은 실제 수신 주소가 없어 임시 값(`https://example.com/webhook`)이다. Webhook secret은 비워 두었다. 앱은 비공개이고 설치한 계정이 없어 이벤트가 전송되지 않는다. 실제 주소가 정해지기 전에는 앱을 설치하지 않고 공개로 전환하지 않는다.

실제 주소가 정해지면 아래 순서로 바꾼다.

1. 임의의 secret을 만들어 Parameter Store의 `/reposcope/webhook-secret`에 저장한다.
2. App 설정의 Webhook secret에 같은 값을 입력한다.
3. Webhook URL을 API Gateway 주소(`/webhook`)로 바꾼다.
4. Advanced → Recent Deliveries에서 응답 코드를 확인한다. 서명이 맞으면 2xx, 틀리면 401이다.
5. 시연 전에 Advanced → Danger zone에서 공개(Any account)로 전환한다. 공개 앱이 다른 계정에 설치되면 다시 비공개로 바꿀 수 없다.

## 비밀값

| 값 | 보관 위치 |
|---|---|
| Private key | Parameter Store `/reposcope/github-app-key` |
| Client secret | Parameter Store `/reposcope/github-client-secret` |
| Webhook secret | Parameter Store `/reposcope/webhook-secret` |

현재 Private key와 Client secret은 만들지 않았다. Parameter Store가 준비된 뒤 생성한다. 키 파일은 저장 직후 로컬에서 삭제한다. 유출이 의심되면 GitHub에서 해당 값을 새로 만들고 이전 값을 삭제한 뒤 Parameter Store를 교체하고 팀에 알린다.
