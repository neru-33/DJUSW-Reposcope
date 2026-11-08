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
| `src/api/client.ts` | API 호출. 쿠키를 보내고 쓰기 요청에 `X-CSRF-Token`을 붙인다 |
| `src/api/types.ts` | API 공통 응답 타입 |
| `src/screens.ts` | 화면 흐름 문서의 화면 목록과 주소 |
| `src/router.tsx` | 주소와 화면 연결 |
| `src/pages/` | 화면 |

API는 [`docs/api-spec.md`](../docs/api-spec.md), 화면 주소는 [`docs/screen-flow.md`](../docs/screen-flow.md)를 따른다.
