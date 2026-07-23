package com.back.coffeeprod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
		"spring.data.redis.host=localhost",
		"spring.data.redis.port=6379",
		"spring.data.redis.password=test",
		"jwt.secret-key=coffeeprod-test-secret-key-32bytes-minimum",
		"jwt.access-expiration=1800000",
		"jwt.refresh-expiration=1209600000",
		"pg.toss.client-key=test-client-key",
		"pg.toss.secret-key=test-secret-key"
})
@DisplayName("애플리케이션 컨텍스트 통합 테스트")
class CoffeeprodApplicationTests {

	@Test
	@DisplayName("스프링 애플리케이션 컨텍스트를 정상적으로 로드한다")
	void contextLoads() {
	}

}
