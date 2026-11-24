package com.blogPlatform.III.blog.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class UsernameValidator implements ConstraintValidator<Username, String> {

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 50;
    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9._-]+$");

    @Override
    public boolean isValid(String username, ConstraintValidatorContext context) {
        if (username == null) {
            return true;
        }
        if (username.length() < MIN_LENGTH || username.length() > MAX_LENGTH) {
            return Violations.reject(context, "must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(username).matches()) {
            return Violations.reject(context, "may contain only letters, digits, '.', '_' and '-'");
        }
        return true;
    }
}
