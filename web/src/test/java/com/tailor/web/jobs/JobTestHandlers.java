package com.tailor.web.jobs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A scripted handler for every job type. The job payload says what to do:
 * <ul>
 *   <li>{@code ok} (default): return {@code {"echo": payload.value}}</li>
 *   <li>{@code reject}: throw a JobRejection (NEEDS_USER with options)</li>
 *   <li>{@code boom}: throw an unexpected exception whose message holds "secret" text</li>
 *   <li>{@code slow}: sleep {@code ms}, then ok (interruptible)</li>
 *   <li>{@code progress}: report a stage and a step count, then ok</li>
 *   <li>{@code hold}: wait for {@link #release(String)} on {@code key}, then ok</li>
 * </ul>
 */
@TestConfiguration
public class JobTestHandlers {

    public static final String SECRET = "SECRET-RESUME-TEXT-9f3a";

    private static final Map<String, CountDownLatch> HOLDS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();

    public static void release(String key) {
        HOLDS.computeIfAbsent(key, k -> new CountDownLatch(1)).countDown();
    }

    public static int runs(Object jobId) {
        AtomicInteger n = RUNS.get(jobId.toString());
        return n == null ? 0 : n.get();
    }

    public static void reset() {
        HOLDS.clear();
        RUNS.clear();
    }

    private static final class Scripted implements JobHandler {
        private final JobType type;

        Scripted(JobType type) {
            this.type = type;
        }

        @Override
        public JobType type() {
            return type;
        }

        @Override
        public Map<String, Object> run(Job job, Context context) throws Exception {
            RUNS.computeIfAbsent(job.id().toString(), k -> new AtomicInteger()).incrementAndGet();
            String behavior = job.payload().path("behavior").asText("ok");
            switch (behavior) {
                case "reject" -> throw new JobRejection("NEEDS_USER", Map.of("options", List.of("SUBSTITUTE_FONTS", "ALLOW_TWO_PAGES")));
                case "boom" -> throw new IllegalStateException(SECRET);
                case "slow" -> TimeUnit.MILLISECONDS.sleep(job.payload().path("ms").asLong(1000));
                case "progress" -> {
                    context.stage("detect");
                    context.step("generating", 2, 6);
                }
                case "events" -> {
                    // What the engine does: stages with a start, steps inside, and an end with what it found.
                    context.events().started("alpha", Map.of("n", 1));
                    TimeUnit.MILLISECONDS.sleep(40);
                    context.events().progress("alpha", Map.of("position", "P0", "project", "quill"));
                    context.events().finished("alpha", Map.of("found", 2, "total", 3));
                    context.stage("beta");
                    TimeUnit.MILLISECONDS.sleep(10);
                }
                case "events-then-boom-once" -> {
                    if (RUNS.get(job.id().toString()).get() == 1) {
                        context.events().started("first-attempt", Map.of());
                        throw new IllegalStateException(SECRET);
                    }
                    context.events().started("second-attempt", Map.of());
                    context.events().finished("second-attempt", Map.of());
                }
                case "hold" -> {
                    String key = job.payload().path("key").asText();
                    if (!HOLDS.computeIfAbsent(key, k -> new CountDownLatch(1)).await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                }
                default -> {
                }
            }
            return Map.of("echo", job.payload().path("value").asText(""));
        }
    }

    @Bean
    JobHandler matchHandler() {
        return new Scripted(JobType.MATCH);
    }

    @Bean
    JobHandler editRevisionHandler() {
        return new Scripted(JobType.EDIT_REVISION);
    }

    @Bean
    JobHandler renderAlternativeHandler() {
        return new Scripted(JobType.RENDER_ALTERNATIVE);
    }

    @Bean
    JobHandler onboardHandler() {
        return new Scripted(JobType.ONBOARD);
    }

    @Bean
    JobHandler generateHandler() {
        return new Scripted(JobType.GENERATE);
    }
}
