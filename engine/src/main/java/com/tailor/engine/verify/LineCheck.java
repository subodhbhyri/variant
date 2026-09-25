package com.tailor.engine.verify;

/** One slot's line-count check: what it should be, what it measured as, and whether they match. */
public record LineCheck(Integer target, Integer measured, boolean ok) {
}