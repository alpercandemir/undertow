package dev.undertow.service;

/** Public errors contain stable source-free codes, never provider bodies or credentials. */
public final class ServiceException extends RuntimeException {
    private final int status;
    private final String code;

    public ServiceException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
