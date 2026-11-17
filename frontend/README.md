# frontend

React와 TypeScript로 작성한 RepoScope 웹 화면이다. Vite로 실행하고 빌드한다.

## 실행

Node.js 22.12 이상이 필요하다.

```bash
npm install
npm run dev
```

개발 서버는 `/api`, `/auth` 요청을 `VITE_DEV_API_TARGET`의 API로 넘긴다. 화면과 API가 같은 주소처럼 동작해 로그인 쿠키가 함께 전달된다.

## 명령

| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 실행 |
| `npm run build` | 타입 검사 후 `dist/`에 빌드 |
| `npm run lint` | 코드 검사 |
| `npm test` | 테스트 실행 |

## 환경 변수

`.env.example`을 복사해 `.env.local`을 만들고 값을 바꾼다. `.env.local`은 커밋하지 않는다.

| 변수 | 설명 | 기본값 |
|---|---|---|
| `VITE_BASE_PATH` | 화면 기본 경로. 예: `/capstone/` | `/` |
| `VITE_API_BASE_URL` | API 주소. 비우면 화면과 같은 주소로 요청한다 | 빈 값 |
| `VITE_DEV_API_TARGET` | 개발 서버가 API 요청을 넘길 주소 | `http://localhost:8080` |

`VITE_`로 시작하는 값은 빌드 결과에 포함되므로 비밀값을 넣지 않는다.

## 폴더

| 경로 | 내용 |
|---|---|
| `src/api/client.ts` | API 호출. 쿠키를 보내고 쓰기 요청에 `X-CSRF-Token`을 붙인다. 로그인 중 401이면 세션 만료로 처리한다 |
| `src/api/useApiData.ts` | 화면이 열릴 때 API를 불러오는 훅 |
| `src/api/types.ts` | API 공통 응답 타입 |
| `src/auth/` | 현재 사용자, 화면 접근 규칙, 직위별 메뉴, 로그인 주소 |
| `src/layout/` | 상단바와 본문 틀 |
| `src/components/` | 불러오는 중, 빈 상태, 오류, 확인 대화상자, 알림 |
| `src/screens.ts` | 화면 흐름 문서의 화면 목록, 주소, 접근 규칙 |
| `src/router.tsx` | 주소와 화면 연결 |
| `src/pages/` | 화면 |
| `src/theme.ts`, `src/index.css` | Mantine 테마와 화면 틀 스타일 |

API는 [`docs/api-spec.md`](../docs/api-spec.md), 화면 주소와 이동은 [`docs/screen-flow.md`](../docs/screen-flow.md), 색과 배치는 [`docs/ui-guide.md`](../docs/ui-guide.md)를 따른다.

## 화면을 만들 때

- 화면은 `src/screens.ts`의 화면을 `src/router.tsx`에서 실제 화면으로 바꿔 끼운다. 접근 규칙은 `RequireAccess`가 검사한다.
- 데이터는 `useApiData`로 불러오고 `DataState`로 불러오는 중, 오류, 권한 없음을 표시한다.
- 저장 결과는 `notifySuccess`, 위험한 동작은 `ConfirmDialog`를 쓴다.
- 테스트는 jsdom에서 실행한다. Mantine 화면은 `MantineProvider`로 감싸서 렌더링한다.
