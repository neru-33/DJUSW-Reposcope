# OSS 라이선스 점검표

| OSS명 | 버전 | 출처 | 라이선스 | 사용 목적 | 공개 가능 여부 |
|---|---|---|---|---|---|
| Spring Boot | 4.1.2 | [Spring Boot](https://github.com/spring-projects/spring-boot/tree/v4.1.2) | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/v4.1.2/LICENSE.txt) | API 서버 및 Web/JDBC 구성 | 라이선스·고지 조건 검토 후 공개 |
| Spring Security | 7.1.2 | [Spring Security](https://github.com/spring-projects/spring-security/tree/7.1.2) | [Apache-2.0](https://github.com/spring-projects/spring-security/blob/7.1.2/LICENSE.txt) | API 인증·권한 및 CSRF 검사 | 라이선스·고지 조건 검토 후 공개 |
| PostgreSQL JDBC Driver | 42.7.14 | [pgJDBC](https://github.com/pgjdbc/pgjdbc/tree/REL42.7.14) | [BSD-2-Clause](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.14/LICENSE) | PostgreSQL 연결 | 라이선스·고지 조건 검토 후 공개 |
| Flyway (flyway-core, flyway-database-postgresql) | 12.4.0 | [Flyway](https://github.com/flyway/flyway/tree/flyway-12.4.0) | [Apache-2.0](https://github.com/flyway/flyway/blob/flyway-12.4.0/LICENSE.txt) | capstone 스키마 마이그레이션 적용 | 라이선스·고지 조건 검토 후 공개 |
| Testcontainers (testcontainers-postgresql) | 2.0.5 | [Testcontainers for Java](https://github.com/testcontainers/testcontainers-java/tree/2.0.5) | [MIT](https://github.com/testcontainers/testcontainers-java/blob/2.0.5/LICENSE) | API의 DB 테스트에서 PostgreSQL 컨테이너 실행 | 개발 도구. 실행 파일(jar)에 포함되지 않음 |
| Gradle | 9.8.2 | [Gradle](https://gradle.org/) | [Apache-2.0](https://docs.gradle.org/current/userguide/licenses.html) | 빌드 및 Gradle Wrapper | 라이선스·고지 조건 검토 후 공개 |
| Eclipse Temurin | 21 (컨테이너 이미지 태그) | [Adoptium Containers](https://github.com/adoptium/containers) | [Temurin 라이선스 정보](https://projects.eclipse.org/projects/adoptium.temurin) | Java 빌드 및 실행 환경 | 이미지 구성 요소와 배포 조건 추가 검토 필요 |
| moto | 5.2.5 (컨테이너 이미지 태그) | [moto](https://github.com/getmoto/moto) | [Apache-2.0](https://github.com/getmoto/moto/blob/master/LICENSE) | 로컬 실행 환경의 AWS(S3, SQS, SSM) 에뮬레이션 | 라이선스·고지 조건 검토 후 공개 |
| PostgreSQL | 17 (`postgres:17-alpine` 이미지, 확인 시점 17.11) | [PostgreSQL](https://www.postgresql.org/), [Docker 이미지](https://github.com/docker-library/postgres) | [PostgreSQL License](https://www.postgresql.org/about/licence/), 이미지 구성 파일 [MIT](https://github.com/docker-library/postgres/blob/master/LICENSE) | 로컬 실행 환경의 데이터베이스 | 이미지 구성 요소와 배포 조건 추가 검토 필요 |
| AWS CLI v2 | 2.39.6 (컨테이너 이미지 태그) | [aws-cli](https://github.com/aws/aws-cli/tree/v2) | [Apache-2.0](https://github.com/aws/aws-cli/blob/v2/LICENSE.txt) | 로컬 실행 환경의 moto 초기화 스크립트 실행 | 라이선스·고지 조건 검토 후 공개 |
| Terraform | 1.17.0 | [Terraform](https://github.com/hashicorp/terraform) | [BUSL-1.1](https://github.com/hashicorp/terraform/blob/main/LICENSE) | AWS 자원 정의와 생성 | 개발 도구. 저장소에 포함하지 않으며 사용 조건 검토 필요 |
| Terraform AWS Provider | 6.72.0 | [terraform-provider-aws](https://github.com/hashicorp/terraform-provider-aws/tree/v6.72.0) | [MPL-2.0](https://github.com/hashicorp/terraform-provider-aws/blob/v6.72.0/LICENSE) | AWS 자원 관리 | 개발 도구. 저장소에 포함하지 않음 |
| Terraform Archive Provider | 2.8.1 | [terraform-provider-archive](https://github.com/hashicorp/terraform-provider-archive/tree/v2.8.1) | [MPL-2.0](https://github.com/hashicorp/terraform-provider-archive/blob/v2.8.1/LICENSE) | Lambda 코드 압축 | 개발 도구. 저장소에 포함하지 않음 |
| boto3 | 1.43.123 | [PyPI](https://pypi.org/project/boto3/1.43.123/) | Apache-2.0 | worker의 SQS, S3 접근 | 라이선스·고지 조건 검토 후 공개 |
| psycopg (`psycopg[binary]`) | 3.3.7 | [PyPI](https://pypi.org/project/psycopg/3.3.7/) | LGPL-3.0 (binary 패키지에 libpq 포함) | worker의 PostgreSQL 연결 | 수정 없이 라이브러리로만 사용. 배포 시 고지 조건 추가 검토 필요 |
| PyJWT (`PyJWT[crypto]`) | 2.15.1 | [PyPI](https://pypi.org/project/PyJWT/2.15.1/) | MIT | worker의 GitHub App JWT 서명 | 공개 가능. 배포 시 라이선스 고지 유지 |
| Python | 3.13 (`python:3.13-slim` 이미지, 확인 시점 3.13.18) | [Python](https://www.python.org/), [Docker 이미지](https://github.com/docker-library/python) | [PSF License](https://docs.python.org/3/license.html), 이미지 구성 파일 [MIT](https://github.com/docker-library/python/blob/master/LICENSE) | worker 실행 환경, worker 테스트 실행 | 이미지 구성 요소와 배포 조건 추가 검토 필요 |
| pytest | 9.2.0 | [PyPI](https://pypi.org/project/pytest/9.2.0/) | MIT | worker 테스트 | 개발 도구. 이미지에 포함되지 않음 |
| React, React DOM | 19.3.1 | [npm](https://www.npmjs.com/package/react/v/19.3.1) | MIT | 웹 화면 구성 | 공개 가능. 배포 시 라이선스 고지 유지 |
| React Router | 7.18.5 | [npm](https://www.npmjs.com/package/react-router/v/7.18.5) | MIT | 화면 주소 라우팅 | 공개 가능. 배포 시 라이선스 고지 유지 |
| Vite | 8.3.6 | [npm](https://www.npmjs.com/package/vite/v/8.3.6) | MIT | 개발 서버, 빌드 | 개발 도구. 빌드 결과에 포함되지 않음 |
| @vitejs/plugin-react | 6.2.1 | [npm](https://www.npmjs.com/package/@vitejs/plugin-react/v/6.2.1) | MIT | Vite의 React 지원 | 개발 도구. 빌드 결과에 포함되지 않음 |
| TypeScript | 7.0.2 | [npm](https://www.npmjs.com/package/typescript/v/7.0.2) | Apache-2.0 | 타입 검사 | 개발 도구. 빌드 결과에 포함되지 않음 |
| Vitest | 5.1.0 | [npm](https://www.npmjs.com/package/vitest/v/5.1.0) | MIT | 테스트 | 개발 도구. 빌드 결과에 포함되지 않음 |
| oxlint | 1.92.0 | [npm](https://www.npmjs.com/package/oxlint/v/1.92.0) | MIT | 코드 검사 | 개발 도구. 빌드 결과에 포함되지 않음 |
| actions/checkout, actions/setup-java, actions/setup-node | checkout v7, setup-java v6, setup-node v7 | [actions](https://github.com/actions) | MIT | CI 소스 내려받기와 실행 환경 준비 | CI 도구. 저장소 결과물에 포함되지 않음 |
| gradle/actions (setup-gradle) | v6 | [gradle/actions](https://github.com/gradle/actions) | MIT(기본 캐시 `cache-provider: basic`만 사용) | CI의 Gradle 설정과 캐시 | CI 도구. 독점 구성 요소인 Enhanced Caching은 사용하지 않음 |

- 위 항목은 직접 사용한 주요 구성 요소다. 간접 의존성, 테스트 의존성과 컨테이너 이미지에 포함된 구성 요소의 라이선스·고지 사항은 배포 전에 추가 확인한다.
- Spring Boot가 관리하는 PostgreSQL JDBC 드라이버와 Flyway 버전은 Gradle `runtimeClasspath`, Testcontainers 버전은 `testRuntimeClasspath`에서 확인했다. 버전 변경 시 다시 확인한다.
- 외부 프로젝트의 소스 코드를 직접 수정하거나 복사하지 않았다. Spring Initializr 생성 파일과 Gradle Wrapper를 사용한다.
- `eclipse-temurin:21-jdk`, `eclipse-temurin:21-jre`는 변경 가능한 태그이므로 이미지 재빌드 시 세부 버전이 달라질 수 있다.
- `postgres:17-alpine`, `python:3.13-slim`은 변경 가능한 태그이므로 이미지를 다시 받으면 세부 버전이 달라질 수 있다.
- worker의 Python 의존성 버전은 `backend/worker/requirements.txt` 기준이다. `boto3`의 `botocore`, `PyJWT[crypto]`의 `cryptography` 등 간접 의존성은 배포 전에 추가 확인한다.
- 프런트엔드 버전은 `frontend/package-lock.json` 기준이다. 의존성을 바꾸면 다시 확인한다.
- 프런트엔드 빌드 결과에 포함되는 런타임 의존성은 간접 의존성을 포함해 6개이고 모두 MIT다.
- 개발 의존성 중 `lightningcss`는 MPL-2.0이다. Vite가 CSS를 처리할 때 쓰는 개발 도구이고, 빌드 결과에 포함되지 않으며 소스를 수정하지 않는다.

## 공개 제한 검토

- 개인정보 포함 여부: 변경 파일 점검에서 발견하지 못함. 최종 검토 필요.
- 기업 제공 자료 포함 여부: 확인 필요.
- 보안 취약점 정보 포함 여부: 확인 필요.
- 데이터 공개 제한 여부: 확인 필요.
- 대체 증빙 필요 여부: 확인 필요.

