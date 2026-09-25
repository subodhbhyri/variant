package com.tailor.engine.edit;

import java.util.List;

/**
 * Thrown by the edit primitives when called on a paragraph {@link
 * com.tailor.engine.slots.UnsupportedReasons} flags — spec section 4.4 says
 * these "fail loudly instead of being substituted," not silently skipped.
 */
public final class UnsupportedBulletException extends RuntimeException {

    public final List<String> reasons;

    public UnsupportedBulletException(List<String> reasons) {
        super("paragraph is not editable: " + reasons);
        this.reasons = reasons;
    }
}
