package com.tailor.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The contract 6B builds against (PHASE6_SPEC.md section 4). Kept deterministic: no host or port. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI tailorOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Resume Tailor API").version("6.0").description(
                        "Every error body is {code, message, details}. The frontend must handle every code."))
                .servers(List.of(new Server().url("/")));
    }
}
