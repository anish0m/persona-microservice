package com.persona.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test - boots the user-service context and fails if any bean is misconfigured.
 *
 * <p>Worth noticing: each service gets its own smoke test. In the monolith one
 * failing context means one failed build. Here, user-service can be green while a
 * sibling service is red - independent build and deploy, which is the whole point.
 */
@SpringBootTest
class UserServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
