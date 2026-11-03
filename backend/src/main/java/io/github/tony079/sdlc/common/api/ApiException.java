package io.github.tony079.sdlc.common.api;

import java.util.List;

import org.springframework.http.HttpStatus;

/** An error with a stable code that is safe to return to the client (TDD 20.1 error envelope). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ApiError.FieldError> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ApiError.FieldError> details() {
        return details;
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The resource was not found.");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    public static ApiException validation(List<ApiError.FieldError> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "One or more fields are invalid.", details);
    }

    public static ApiException versionChanged() {
        return conflict("VERSION_CHANGED", "This resource was changed by someone else. Reload and try again.");
    }

    public static ApiException preconditionRequired() {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                "The If-Match header with the current version is required.");
    }
}
