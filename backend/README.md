# backend

서버 쪽 코드를 보관한다. API와 수집 worker는 별도로 빌드하고 배포하는 애플리케이션이다.

```text
backend/
├─ api/          Spring Boot API
│  ├─ src/main/java/
│  ├─ src/main/resources/db/migration/   capstone 스키마 마이그레이션 (Flyway)
│  └─ src/test/
├─ worker/       수집 worker
└─ migrations/   수집·계정 테이블 마이그레이션 (worker가 시작할 때 적용)
```

- 각 애플리케이션은 자기 폴더 안에 빌드 설정, Dockerfile, 테스트를 둔다.
- 두 애플리케이션은 서로의 코드를 가져다 쓰지 않는다. 주고받는 데이터는 [데이터 모델](../docs/data-model.md)을 따른다.
