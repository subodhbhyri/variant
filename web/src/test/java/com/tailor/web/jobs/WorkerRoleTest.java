package com.tailor.web.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailor.web.TestDatabase;
import com.tailor.web.WebApplication;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The worker role end to end: no web server, its own loop claims and finishes a queued job. */
class WorkerRoleTest {

    /** A scripted handler for one type (the real onboard handler is also present in a worker). */
    @org.springframework.boot.test.context.TestConfiguration
    static class MatchOnly {
        @org.springframework.context.annotation.Bean
        JobHandler matchHandler() {
            return new JobHandler() {
                @Override
                public JobType type() {
                    return JobType.MATCH;
                }

                @Override
                public Map<String, Object> run(Job job, Context context) {
                    return Map.of("echo", job.payload().path("value").asText(""));
                }
            };
        }
    }

    @Test
    void aWorkerProcessPicksUpAndFinishesAJob() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(WebApplication.class, MatchOnly.class)
                .web(WebApplicationType.NONE)
                .run("--app.role=worker", "--app.renderer.url=http://localhost:9",
                        "--app.jobs.poll-interval=50ms", "--app.jobs.concurrency=2",
                        "--spring.datasource.url=" + TestDatabase.url(),
                        "--spring.datasource.username=" + TestDatabase.user(),
                        "--spring.datasource.password=" + TestDatabase.password())) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            JobService jobs = ctx.getBean(JobService.class);
            UUID user = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", user, "worker-" + user + "@example.com");

            Job job = jobs.enqueue(user, JobType.MATCH, Map.of("behavior", "ok", "value", "from-worker"), null);

            Job done = null;
            for (int i = 0; i < 100 && (done == null || !done.status().terminal()); i++) {
                Thread.sleep(100);
                done = ctx.getBean(JobRepository.class).find(job.id()).orElseThrow();
            }
            assertThat(done.status()).isEqualTo(Job.Status.SUCCEEDED);
            assertThat(done.result().path("echo").asText()).isEqualTo("from-worker");
            assertThat(done.workerId()).isEqualTo(ctx.getBean(JobProcessor.class).workerId());
            assertThat(ctx.getBean(JobProcessor.class).handledTypes())
                    .as("a real worker has the real onboarding handler").contains(JobType.ONBOARD, JobType.MATCH);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }
}
