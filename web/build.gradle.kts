plugins {
    id("java")
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

dependencies {
    // PHASE6_SPEC.md section 2: the web module wraps the engine and never changes it.
    implementation(project(":engine"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Section 9.1: server-side sessions in PostgreSQL, Google OpenID Connect, CSRF.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.session:spring-session-jdbc")

    // Section 3: Flyway migrations on PostgreSQL 16.
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Section 4: OpenAPI 3.1 generated from the code. API docs only, no Swagger UI.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.6.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// A plain library-style jar is not runnable; the image runs the Boot jar.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("web")
}
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test> {
    testLogging {
        // Show why an assertion failed in the console output (the report is inside a throwaway container).
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
