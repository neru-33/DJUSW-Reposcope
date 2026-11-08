# OSS 라이선스 점검표

| OSS명 | 버전 | 출처 | 라이선스 | 사용 목적 | 공개 가능 여부 |
|---|---|---|---|---|---|
| Spring Boot | 4.1.2 | [Spring Boot](https://github.com/spring-projects/spring-boot/tree/v4.1.2) | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/v4.1.2/LICENSE.txt) | API 서버 및 Web/JDBC 구성 | 라이선스·고지 조건 검토 후 공개 |
| PostgreSQL JDBC Driver | 42.7.14 | [pgJDBC](https://github.com/pgjdbc/pgjdbc/tree/REL42.7.14) | [BSD-2-Clause](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.14/LICENSE) | PostgreSQL 연결 | 라이선스·고지 조건 검토 후 공개 |
| Gradle | 9.8.2 | [Gradle](https://gradle.org/) | [Apache-2.0](https://docs.gradle.org/current/userguide/licenses.html) | 빌드 및 Gradle Wrapper | 라이선스·고지 조건 검토 후 공개 |
| Eclipse Temurin | 21 (컨테이너 이미지 태그) | [Adoptium Containers](https://github.com/adoptium/containers) | [Temurin 라이선스 정보](https://projects.eclipse.org/projects/adoptium.temurin) | Java 빌드 및 실행 환경 | 이미지 구성 요소와 배포 조건 추가 검토 필요 |

- 위 항목은 직접 사용한 주요 구성 요소다. 간접 의존성, 테스트 의존성과 컨테이너 이미지에 포함된 구성 요소의 라이선스·고지 사항은 배포 전에 추가 확인한다.
- Spring Boot가 관리하는 PostgreSQL JDBC 드라이버 버전은 Gradle `runtimeClasspath`에서 확인했다. 버전 변경 시 다시 확인한다.
- 외부 프로젝트의 소스 코드를 직접 수정하거나 복사하지 않았다. Spring Initializr 생성 파일과 Gradle Wrapper를 사용한다.
- `eclipse-temurin:21-jdk`, `eclipse-temurin:21-jre`는 변경 가능한 태그이므로 이미지 재빌드 시 세부 버전이 달라질 수 있다.

## 공개 제한 검토

- 개인정보 포함 여부: 변경 파일 점검에서 발견하지 못함. 최종 검토 필요.
- 기업 제공 자료 포함 여부: 확인 필요.
- 보안 취약점 정보 포함 여부: 확인 필요.
- 데이터 공개 제한 여부: 확인 필요.
- 대체 증빙 필요 여부: 확인 필요.

