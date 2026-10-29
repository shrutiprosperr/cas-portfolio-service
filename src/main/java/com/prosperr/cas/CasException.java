package com.prosperr.cas;

/** A parse failure the caller can branch on by code. The password is never included. */
public final class CasException extends RuntimeException {
    public static final String PASSWORD_REQUIRED = "PASSWORD_REQUIRED";
    public static final String PASSWORD_INCORRECT = "PASSWORD_INCORRECT";
    public static final String UNREADABLE_PDF = "UNREADABLE_PDF";
    public static final String NO_TEXT_LAYER = "NO_TEXT_LAYER";
    public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    public static final String FORMAT_NOT_IN_PROTOTYPE = "FORMAT_NOT_IN_PROTOTYPE";

    private final String code;

    public CasException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
