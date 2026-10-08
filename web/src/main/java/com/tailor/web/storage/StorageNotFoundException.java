package com.tailor.web.storage;

public class StorageNotFoundException extends RuntimeException {
    public StorageNotFoundException(String key) {
        super("no such object: " + key);
    }
}
