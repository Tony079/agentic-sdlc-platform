package io.github.tony079.sdlc.modelgateway.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * JSON Schema for strict structured output, generated from an output record: every property listed as required and
 * no additional properties (the strict-mode rules); fields without @NotNull/@NotBlank/@NotEmpty may be null; enums
 * list their labels. Lengths and counts stay with Bean Validation after binding. Supports records, enums, String,
 * Integer/Long, Boolean, Double and List.
 */
final class JsonSchemas {

    private JsonSchemas() {
    }

    static Map<String, Object> forType(Class<?> type) {
        if (!type.isRecord()) {
            return null;
        }
        return object(type);
    }

    private static Map<String, Object> object(Class<?> type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent c : type.getRecordComponents()) {
            Map<String, Object> schema = schema(c.getGenericType());
            properties.put(c.getName(), required(c) ? schema : nullable(schema));
            required.add(c.getName());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        out.put("required", required);
        out.put("additionalProperties", false);
        return out;
    }

    private static Map<String, Object> schema(Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            return Map.of("type", "array", "items", schema(p.getActualTypeArguments()[0]));
        }
        if (type instanceof Class<?> c) {
            if (c.isRecord()) {
                return object(c);
            }
            if (c.isEnum()) {
                return Map.of("type", "string", "enum",
                        Arrays.stream(c.getEnumConstants()).map(e -> ((Enum<?>) e).name()).toList());
            }
            if (c == String.class) {
                return Map.of("type", "string");
            }
            if (c == Integer.class || c == int.class || c == Long.class || c == long.class) {
                return Map.of("type", "integer");
            }
            if (c == Boolean.class || c == boolean.class) {
                return Map.of("type", "boolean");
            }
            if (c == Double.class || c == double.class) {
                return Map.of("type", "number");
            }
        }
        throw new IllegalArgumentException("No JSON Schema mapping for " + type);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static boolean required(RecordComponent c) {
        return c.getType().isPrimitive() || present(c, NotNull.class) || present(c, NotBlank.class)
                || present(c, NotEmpty.class);
    }

    private static boolean present(RecordComponent c, Class<? extends java.lang.annotation.Annotation> a) {
        return c.isAnnotationPresent(a) || c.getAccessor().isAnnotationPresent(a)
                || c.getAnnotatedType().isAnnotationPresent(a);
    }
}
