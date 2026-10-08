package com.tailor.web;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The PostgreSQL the web tests run against. They need a real server (Flyway, triggers, SKIP LOCKED
 * and partial indexes are PostgreSQL behaviour), so a missing server is a failure, not a skip.
 * {@code scripts/web-test.ps1} starts one and sets {@code TEST_DB_URL}.
 */
public final class TestDatabase {

    public static final String URL_ENV = "TEST_DB_URL";

    private TestDatabase() {
    }

    public static String url() {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(URL_ENV + " is not set. Run the web tests with"
                    + " scripts/web-test.ps1, which starts PostgreSQL and sets it.");
        }
        return url;
    }

    public static String user() {
        return System.getenv().getOrDefault("TEST_DB_USER", "tailor");
    }

    public static String password() {
        return System.getenv().getOrDefault("TEST_DB_PASSWORD", "tailor");
    }

    /** Points a Spring context at the test server's {@code public} schema. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    /** A fresh schema with every migration applied; drop it with {@link #dropSchema}. */
    public static String createMigratedSchema() {
        String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(url(), user(), password())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return schema;
    }

    /** A connection pool-free data source whose search_path is {@code schema}. */
    public static DataSource inSchema(String schema) {
        String url = url();
        String sep = url.contains("?") ? "&" : "?";
        return new DriverManagerDataSource(url + sep + "currentSchema=" + schema, user(), password());
    }

    public static void dropSchema(String schema) throws SQLException {
        try (Connection c = new DriverManagerDataSource(url(), user(), password()).getConnection();
                Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
