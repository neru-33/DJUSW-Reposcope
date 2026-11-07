# 기여 규칙

## 저장소 구조

```text
DJUSW-Reposcope/
├─ frontend/     React 웹 화면
├─ backend/
│  ├─ api/        Spring Boot API
│  ├─ worker/     수집 worker
│  └─ migrations/ 수집·계정 테이블 마이그레이션
├─ webhook/      GitHub Webhook 수신 Lambda
├─ infra/        AWS 구성·배포 설정
├─ docs/         계획서, 주간 기록, 회의록, OSS 라이선스 검토
├─ reports/      중간보고서, 결과보고서, 발표자료
├─ assets/       공개 가능한 이미지, 다이어그램, 시연 캡처
├─ data/         공개 가능한 데이터 설명
│  └─ sample/    공개 가능한 샘플 데이터
└─ .github/      Issue·PR 템플릿
```

- 테스트 코드는 각 앱 폴더 안에 둔다.
- 수동 검증만 가능하면 절차와 결과를 `reports/` 또는 `assets/`에 정리한다.
- 주간 활동은 `docs/weekly-log.md`에 실제 수행 내용과 이슈·PR·결과물 링크를 기록한다.

## 작업 흐름

```text
Issue 생성 → 브랜치 생성 → 커밋 → PR → 리뷰 승인 → main에 Squash merge → 브랜치 자동 삭제
```

1. **Issue 만들기:** 기능, 버그, 작업 양식 중 하나를 골라 작성한다.
2. **브랜치 만들기:** 최신 `main`에서 이슈 번호를 넣어 만든다.

   ```bash
   git switch main
   git pull
   git switch -c feat/12-repository-list
   ```

3. **PR 올리기:** 본문에 `Closes #12`를 적으면 병합할 때 이슈가 자동으로 닫힌다.
4. **리뷰 받기:** 작성자가 아닌 팀원 1명 이상의 승인을 받는다.
5. **병합하기:** Squash merge만 사용한다. PR 하나가 `main`의 커밋 하나가 된다.

## 브랜치 이름

`<타입>/<이슈번호>-<짧은-설명>` 형식을 사용한다. 영문 소문자, 숫자, 하이픈, 슬래시만 쓰고 공백과 한글은 쓰지 않는다.

| 타입 | 용도 | 예시 |
|---|---|---|
| `feat` | 기능 추가 | `feat/12-repository-list` |
| `fix` | 오류 수정 | `fix/20-webhook-signature` |
| `docs` | 문서 수정 | `docs/7-api-spec` |
| `refactor` | 구조 개선 | `refactor/15-sync-service` |
| `test` | 테스트 추가 | `test/18-commit-upsert` |
| `chore` | 설정·환경 정리 | `chore/3-setup-lint` |

## 커밋 메시지

[Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/) 형식을 사용한다. 타입은 브랜치 타입과 같고, 범위(scope)에는 폴더 이름을 적는다.

```text
feat(frontend): 저장소 목록 화면 추가
fix(webhook): 서명 검증 실패 응답 수정
chore(infra): SQS 재시도 설정 변경
docs: 주간 기록 갱신
```

Squash merge에서는 **PR 제목이 최종 커밋 메시지**가 되므로 PR 제목도 같은 형식으로 쓴다.

## main 보호 규칙

| 규칙 | 내용 |
|---|---|
| 직접 push | 금지. 반드시 PR로 합친다. |
| 강제 push (`--force`) | 금지 |
| 브랜치 삭제 | 금지 |
| 병합 조건 | 승인 1명 이상, Squash merge |
| 예외 | 저장소 소유자는 긴급 수정에 한해 규칙을 우회할 수 있다. |

## develop 브랜치

`develop`은 1차 배포 후 만든다. 만들 때 아래 내용을 저장소 설정과 이 문서에 함께 반영한다.

- 작업 브랜치는 `develop`에서 만들고 `develop`에 Squash merge한다.
- `develop`에도 `main`과 같은 보호 규칙을 적용한다.
- `develop` → `main`은 Merge commit으로 합친다. Squash로 합치면 두 브랜치의 이력이 갈라져 다음 병합 때 충돌한다.

## 공개 저장소 주의사항

- 기업 자료, 개인정보, 비공개 데이터, 보안 취약점 정보는 올리지 않는다.
- 실제 비밀번호, API 키, GitHub App 비밀키(`.pem`)는 올리지 않는다.
- `.env.example`은 설정 항목의 예시다. 실제 값을 입력한 `.env`는 커밋하지 않는다.
- 외부에서 받은 코드는 출처와 라이선스를 `docs/oss-license-check.md`에 기록한다.
- 라이선스는 [LICENSE_NOTICE.md](LICENSE_NOTICE.md)의 절차에 따라 정한다.
- 지도교수 또는 기업 멘토의 검토가 필요한 항목은 PR 본문에 표시한다.
