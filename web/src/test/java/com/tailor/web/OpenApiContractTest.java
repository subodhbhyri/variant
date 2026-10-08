package com.tailor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P6-T12: {@code web/openapi.json} is generated from the code and the committed file must match it
 * (CI fails on drift). Regenerate it with {@code scripts/web-test.ps1 -UpdateOpenApi}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final Path COMMITTED = Path.of("openapi.json");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void committedOpenApiMatchesTheCode() throws Exception {
        String served = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper pretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode generated = pretty.readTree(served);
        assertThat(generated.get("openapi").asText()).startsWith("3.1");
        String generatedText = pretty.writeValueAsString(generated) + "\n";

        if ("1".equals(System.getenv("OPENAPI_UPDATE"))) {
            Files.writeString(COMMITTED, generatedText, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(COMMITTED))
                .as("web/openapi.json is missing; run scripts/web-test.ps1 -UpdateOpenApi").isTrue();
        String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(committed)
                .as("web/openapi.json is out of date with the code; run scripts/web-test.ps1 -UpdateOpenApi")
                .isEqualTo(generatedText);
    }

    @Test
    void protectedPathsUseTheSharedErrorShapeWhenAnonymous() throws Exception {
        // Unknown and protected paths look the same to an anonymous caller, so nothing can be probed.
        mvc.perform(get("/nope")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void healthEndpointAnswersWithTheRole() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.role").value("api"));
    }
}
