package com.tailor.renderer;

/** A render that did not produce a PDF, with the section 2.1 error code the service answers with. */
public final class RenderFailure extends Exception {

    public enum Code {
        RENDER_TIMEOUT(504),
        RENDER_FAILED(500);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;
    private final boolean callerFault;

    public RenderFailure(Code code, String message) {
        super(message);
        this.code = code;
        this.callerFault = false;
    }

    private RenderFailure(Code code, String message, boolean callerFault) {
        super(message);
        this.code = code;
        this.callerFault = callerFault;
    }

    /** The request itself is wrong (not a .docx); answered with 400, and a retry would fail the same way. */
    public static RenderFailure badInput(String message) {
        return new RenderFailure(Code.RENDER_FAILED, message, true);
    }

    public RenderFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.callerFault = false;
    }

    public Code code() {
        return code;
    }

    public boolean callerFault() {
        return callerFault;
    }
}
