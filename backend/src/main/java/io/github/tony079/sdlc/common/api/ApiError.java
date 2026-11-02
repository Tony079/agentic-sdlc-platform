package io.github.tony079.sdlc.common.api;

/** Error envelope: {"error":{"code","message","correlationId"}} (TDD 20.1). Never contains internal detail. */
public record ApiError(Body error) {

    public record Body(String code, String message, String correlationId) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(new Body(code, message, CorrelationId.current().orElse(null)));
    }
}
