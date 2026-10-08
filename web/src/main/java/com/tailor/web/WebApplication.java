package com.tailor.web;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        AppRole role = AppRole.parse(System.getenv(AppRole.ENV));
        SpringApplicationBuilder app = new SpringApplicationBuilder(WebApplication.class)
                .properties("app.role=" + role.property());
        if (role == AppRole.WORKER) {
            // A worker has no HTTP surface (its health is its job heartbeat), so no web server starts.
            app.web(WebApplicationType.NONE);
        }
        app.run(args);
    }
}
