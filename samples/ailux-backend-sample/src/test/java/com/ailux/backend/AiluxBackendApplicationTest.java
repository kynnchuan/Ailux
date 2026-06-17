package com.ailux.backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "ailux.providers.deepseek.api-key=test-key",
    "ailux.providers.openai.api-key=test-key"
})
@DisplayName("Application Context - Spring Boot smoke test")
class AiluxBackendApplicationTest {

    @Test
    @DisplayName("Application context loads successfully")
    void contextLoads() {
        // Verifies that the Spring context starts up correctly:
        // - All beans are wired
        // - Database schema is created
        // - Configuration properties are bound
    }
}
