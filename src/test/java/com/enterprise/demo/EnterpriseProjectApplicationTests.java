package com.enterprise.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "notification.service.url=http://localhost:8081/notifications")
class EnterpriseProjectApplicationTests {

	@Test
	void contextLoads() {
	}

}
