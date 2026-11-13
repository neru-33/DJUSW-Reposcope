package com.djusw.api.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * DB가 필요한 테스트에 붙인다. PostgreSQL 컨테이너에 마이그레이션을 적용한 뒤 연결한다.
 * 이 애너테이션을 쓰는 테스트는 같은 설정을 공유하므로 컨테이너가 한 번만 뜬다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.flyway.enabled=true")
@Import(TestDatabaseConfiguration.class)
public @interface DatabaseTest {
}
