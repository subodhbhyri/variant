package com.tailor.engine.render;

/** Thrown when a document could not be rendered: process failure, timeout, or missing output. */
public class RenderException extends Exception {
    public RenderException(String message) {
        super(message);
    }

    public RenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
