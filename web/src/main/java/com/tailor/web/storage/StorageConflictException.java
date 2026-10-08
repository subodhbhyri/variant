package com.tailor.web.storage;

/** An object already exists at a key that must never be overwritten. */
public class StorageConflictException extends RuntimeException {
    public StorageConflictException(String key) {
        super("object already exists: " + key);
    }
}
