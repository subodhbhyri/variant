package com.tailor.web.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The api with scripted job handlers for every job type (fast; no files, no LibreOffice). Used by
 * the auth, session and job-queue tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(com.tailor.web.jobs.JobTestHandlers.class)
public abstract class ApiTestBase extends AbstractApiTest {

    protected static final int PORT = freePort();
    protected static final String BASE = "http://localhost:" + PORT;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        commonProperties(registry, PORT);
    }

    @Override
    protected String baseUrl() {
        return BASE;
    }
}
