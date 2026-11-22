package com.qualiguard.qms;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 앱 전체 기동 시험. SQL Server·Kafka·Gemini 키(.env)가 있어야 통과하므로 통합 시험으로 표시한다.
 * CI(CI=true)에서는 제외되고, 맥에서 docker compose 를 띄운 뒤 ./gradlew test 로 함께 돈다.
 */
@Tag("integration")
@SpringBootTest
class QmsAppApplicationTests {

	@Test
	void contextLoads() {
	}

}
