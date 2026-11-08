# OSS 라이선스 점검표

| OSS명 | 버전 | 출처 | 라이선스 | 사용 목적 | 공개 가능 여부 |
|---|---|---|---|---|---|
| React, React DOM | 19.3.1 | [npm](https://www.npmjs.com/package/react/v/19.3.1) | MIT | 웹 화면 구성 | 공개 가능. 배포 시 라이선스 고지 유지 |
| React Router | 7.18.5 | [npm](https://www.npmjs.com/package/react-router/v/7.18.5) | MIT | 화면 주소 라우팅 | 공개 가능. 배포 시 라이선스 고지 유지 |
| Vite | 8.3.6 | [npm](https://www.npmjs.com/package/vite/v/8.3.6) | MIT | 개발 서버, 빌드 | 개발 도구. 빌드 결과에 포함되지 않음 |
| @vitejs/plugin-react | 6.2.1 | [npm](https://www.npmjs.com/package/@vitejs/plugin-react/v/6.2.1) | MIT | Vite의 React 지원 | 개발 도구. 빌드 결과에 포함되지 않음 |
| TypeScript | 7.0.2 | [npm](https://www.npmjs.com/package/typescript/v/7.0.2) | Apache-2.0 | 타입 검사 | 개발 도구. 빌드 결과에 포함되지 않음 |
| Vitest | 5.1.0 | [npm](https://www.npmjs.com/package/vitest/v/5.1.0) | MIT | 테스트 | 개발 도구. 빌드 결과에 포함되지 않음 |
| oxlint | 1.92.0 | [npm](https://www.npmjs.com/package/oxlint/v/1.92.0) | MIT | 코드 검사 | 개발 도구. 빌드 결과에 포함되지 않음 |

- 프런트엔드 버전은 `frontend/package-lock.json` 기준이다. 의존성을 바꾸면 다시 확인한다.
- 프런트엔드 빌드 결과에 포함되는 런타임 의존성은 간접 의존성을 포함해 6개이고 모두 MIT다.
- 개발 의존성 중 `lightningcss`는 MPL-2.0이다. Vite가 CSS를 처리할 때 쓰는 개발 도구이고, 빌드 결과에 포함되지 않으며 소스를 수정하지 않는다.

## 공개 제한 검토

- 개인정보 포함 여부:
- 기업 제공 자료 포함 여부:
- 보안 취약점 정보 포함 여부:
- 데이터 공개 제한 여부:
- 대체 증빙 필요 여부:

