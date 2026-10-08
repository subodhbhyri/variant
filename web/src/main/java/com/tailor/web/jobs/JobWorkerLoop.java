package com.tailor.web.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The worker role's main loop: {@code concurrency} threads, each claiming and running one job at a
 * time. On shutdown it stops claiming and gives running jobs a short time to finish; anything
 * still running loses its lease and is handled by whichever worker reaps it.
 */
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class JobWorkerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerLoop.class);

    private final JobProcessor processor;
    private final JobProperties props;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public JobWorkerLoop(JobProcessor processor, JobProperties props) {
        this.processor = processor;
        this.props = props;
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < props.concurrency(); i++) {
            Thread t = new Thread(this::loop, "job-worker-" + i);
            t.setDaemon(false);
            threads.add(t);
            t.start();
        }
        log.info("worker {} started: {} threads, handling {}", processor.workerId(), props.concurrency(),
                processor.handledTypes());
    }

    private void loop() {
        while (running) {
            try {
                if (!processor.runNext()) {
                    TimeUnit.MILLISECONDS.sleep(props.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A database blip must not kill the worker thread.
                log.error("worker loop error: {}", e.getClass().getName());
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        for (Thread t : threads) {
            try {
                t.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            t.interrupt();
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
