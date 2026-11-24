package com.blogPlatform.III.blog.exception;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.security.SecurityErrorHandler;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String VALIDATION_FAILED = "Validation failed";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ContentRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleContentRejected(ContentRejectedException ex,
                                                                   HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    // one message for a wrong username and a wrong password: nobody can find out which usernames exist
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.UNAUTHORIZED, "Invalid username or password", request.getRequestURI());
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyLogins(TooManyLoginAttemptsException ex,
                                                                 HttpServletRequest request) {
        ApiResponse<Void> body = ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request.getRequestURI()).getBody();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(body);
    }

    // a @PreAuthorize that says no. It is thrown inside the controller call, so this class sees it before
    // Spring Security does, and without this method the catch-all below would answer 500
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.FORBIDDEN, SecurityErrorHandler.ACCESS_DENIED, request.getRequestURI());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.CONFLICT,
                "This record was changed by someone else. Load it again and retry your change.",
                request.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(HttpServletRequest request) {
        return ApiResponse.error(HttpStatus.CONFLICT, "The data conflicts with an existing record",
                request.getRequestURI());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(BindException ex, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        addErrors(errors, ex);
        return ApiResponse.error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors, request.getRequestURI());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterValidation(HandlerMethodValidationException ex,
                                                                       HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors objectErrors) {
                addErrors(errors, objectErrors);
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(error -> addError(errors, name, error.getDefaultMessage()));
            }
        }
        return ApiResponse.error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors, request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        Map<String, String> errors = Map.of(ex.getName(), "invalid value '" + shorten(ex.getValue()) + "'");
        return ApiResponse.error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors, request.getRequestURI());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                    HttpServletRequest request) {
        Map<String, String> errors = Map.of(ex.getParameterName(), "is required");
        return ApiResponse.error(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, errors, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadJson(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        Throwable cause = ex.getMostSpecificCause();
        Map<String, String> errors = new LinkedHashMap<>();

        if (cause instanceof UnrecognizedPropertyException unknown) {
            addError(errors, jsonPath(unknown), "unknown field");
        } else if (cause instanceof MismatchedInputException wrongType && !wrongType.getPath().isEmpty()) {
            addError(errors, jsonPath(wrongType), "must be " + describe(wrongType.getTargetType()));
        } else {
            return ApiResponse.error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON",
                    request.getRequestURI());
        }
        return ApiResponse.error(HttpStatus.BAD_REQUEST, "Request body is not valid", errors, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            return ApiResponse.error(errorResponse.getStatusCode(), errorResponse.getBody().getDetail(),
                    request.getRequestURI());
        }

        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong, please try again later",
                request.getRequestURI());
    }

    private static void addError(Map<String, String> errors, String field, String message) {
        errors.merge(field, message, (first, second) -> first + "; " + second);
    }

    // field errors under the field's name, errors of the whole object (e.g. @AssertTrue) under the object's name
    private static void addErrors(Map<String, String> errors, Errors source) {
        source.getFieldErrors().forEach(error -> addError(errors, error.getField(), messageOf(error)));
        source.getGlobalErrors().forEach(error -> addError(errors, error.getObjectName(), error.getDefaultMessage()));
    }

    private static String messageOf(FieldError error) {
        return error.isBindingFailure()
                ? "invalid value '" + shorten(error.getRejectedValue()) + "'"
                : error.getDefaultMessage();
    }

    private static String jsonPath(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : ex.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "body" : path.toString();
    }

    private static String describe(Class<?> type) {
        if (type == null) {
            return "a different type";
        }
        if (type.isEnum()) {
            return "one of " + Arrays.toString(type.getEnumConstants());
        }
        if (type == Integer.class || type == Long.class || type == int.class || type == long.class) {
            return "a whole number";
        }
        if (Number.class.isAssignableFrom(type) || type == double.class) {
            return "a number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (type == String.class) {
            return "text";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "a list";
        }
        return "a " + type.getSimpleName();
    }

    private static String shorten(Object value) {
        String text = String.valueOf(value);
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
