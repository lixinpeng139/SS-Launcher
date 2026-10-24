package com.sslauncher.auth;

/** Authentication failure with a message that is safe to show to the user. */
public class AuthException extends Exception {
    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
