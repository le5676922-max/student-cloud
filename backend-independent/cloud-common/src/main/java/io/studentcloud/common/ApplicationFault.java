package io.studentcloud.common;

/** A public, non-sensitive business failure; transport adapters map its status and code. */
public final class ApplicationFault extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public ApplicationFault(String code, int httpStatus, String message) {
        super(message);
        if (code == null || code.isBlank() || httpStatus < 400 || httpStatus > 599) {
            throw new IllegalArgumentException("Invalid application failure classification");
        }
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
