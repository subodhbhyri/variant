package com.tailor.web.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailor.web.api.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** What the api side does with jobs: queue them, and look them up for their owner. */
@Service
public class JobService {

    static final int MAX_KEY_LENGTH = 200;

    private final JobRepository jobs;
    private final ObjectMapper json;

    public JobService(JobRepository jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    /**
     * Queues a job for {@code userId}. With an {@code Idempotency-Key}, the same key within 24 hours
     * returns the job it created the first time (PHASE6_SPEC.md section 5).
     */
    public Job enqueue(UUID userId, JobType type, Object payload, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "The Idempotency-Key header is too long.");
        }
        JsonNode body = payload instanceof JsonNode node ? node : json.valueToTree(payload);
        return jobs.enqueue(userId, type, body, key);
    }

    public Job getForUser(UUID jobId, UUID userId) {
        return jobs.findForUser(jobId, userId).orElseThrow(ApiException::notFound);
    }
}
