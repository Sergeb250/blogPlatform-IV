package com.blogPlatform.III.blog.validation;

import jakarta.validation.ConstraintValidatorContext;

final class Violations {

    private Violations() {
    }

    // replaces the annotation's default message with this one; false, so a validator can "return reject(...)"
    static boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
