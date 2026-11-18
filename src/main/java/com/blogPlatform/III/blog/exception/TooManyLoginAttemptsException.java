package com.blogPlatform.III.blog.exception;

import lombok.Getter;

import java.time.Duration;

@Getter
public class TooManyLoginAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super("Too many failed logins. Try again in " + minutesRoundedUp(retryAfter) + " minute(s)");
        this.retryAfter = retryAfter;
    }

    private static long minutesRoundedUp(Duration duration) {
        return (duration.toSeconds() + 59) / 60;
    }
}
