package io.github.tony079.sdlc.common.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error envelope: {"error":{"code","message","correlationId","details"?}} (TDD 20.1). Never contains internal
 * detail. {@code details} lists field-level problems for 422 responses.
 */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(String code, String message, String correlationId, List<FieldError> details) {
    }

    /** A problem with one request field. Codes: REQUIRED, TOO_LONG, INVALID_FORMAT, INVALID_VALUE, MUST_BE_ABSENT. */
    public record FieldError(String field, String code) {
    }

    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(new Body(code, message, CorrelationId.current().orElse(null), details));
    }
}
