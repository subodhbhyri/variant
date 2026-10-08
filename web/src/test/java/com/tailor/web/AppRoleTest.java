package com.tailor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class AppRoleTest {

    @Test
    void parsesTheEnvironmentValue() {
        assertThat(AppRole.parse(null)).isEqualTo(AppRole.API);
        assertThat(AppRole.parse("")).isEqualTo(AppRole.API);
        assertThat(AppRole.parse("api")).isEqualTo(AppRole.API);
        assertThat(AppRole.parse(" Worker ")).isEqualTo(AppRole.WORKER);
        assertThatThrownBy(() -> AppRole.parse("renderer")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerRoleHasNoWebServerAndNoControllers() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(WebApplication.class)
                .web(WebApplicationType.NONE)
                .run("--app.role=worker", "--app.renderer.url=http://localhost:9",
                        "--spring.datasource.url=" + TestDatabase.url(),
                        "--spring.datasource.username=" + TestDatabase.user(),
                        "--spring.datasource.password=" + TestDatabase.password())) {
            assertThat(ctx.getBeanNamesForType(com.tailor.web.api.HealthController.class)).isEmpty();
        }
    }
}
