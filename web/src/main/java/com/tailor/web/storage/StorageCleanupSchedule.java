package com.tailor.web.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the cleaner every minute on the worker. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class StorageCleanupSchedule {

    private final StorageCleaner cleaner;

    public StorageCleanupSchedule(StorageCleaner cleaner) {
        this.cleaner = cleaner;
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup-interval-ms:60000}", initialDelay = 10_000)
    void run() {
        cleaner.runOnce();
    }
}
