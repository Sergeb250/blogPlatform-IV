package com.blogPlatform.III.blog.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class TagNameValidator implements ConstraintValidator<TagName, String> {

    static final int MAX_LENGTH = 50;
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._+#-]*$");

    @Override
    public boolean isValid(String tagName, ConstraintValidatorContext context) {
        if (tagName == null) {
            return true;
        }
        if (tagName.length() > MAX_LENGTH) {
            return Violations.reject(context, "must be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(tagName).matches()) {
            return Violations.reject(context,
                    "must start with a letter or digit and may contain only letters, digits, spaces and . _ + # -");
        }
        return true;
    }
}
