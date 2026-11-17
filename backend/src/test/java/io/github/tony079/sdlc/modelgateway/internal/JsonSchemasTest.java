package io.github.tony079.sdlc.modelgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.junit.jupiter.api.Test;

class JsonSchemasTest {

    enum Kind { FACT, QUESTION }

    record Evidence(@NotBlank String quote, Integer line) {
    }

    record Item(@NotNull Kind kind, @NotBlank String statement, @NotNull List<Evidence> evidence, String risk) {
    }

    record Out(@NotNull List<Item> items, Item first) {
    }

    @Test
    @SuppressWarnings("unchecked")
    void strictSchemaListsEveryPropertyClosesObjectsAndConstrainsEnums() {
        Map<String, Object> schema = JsonSchemas.forType(Out.class);
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("items", "first"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) props.get("items")).get("items");
        assertThat(item.get("required")).isEqualTo(List.of("kind", "statement", "evidence", "risk"));
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        assertThat(itemProps.get("kind")).isEqualTo(Map.of("type", "string", "enum", List.of("FACT", "QUESTION")));
        // Optional fields may be null; required ones may not.
        assertThat(itemProps.get("risk")).isEqualTo(Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null"))));
        assertThat(itemProps.get("statement")).isEqualTo(Map.of("type", "string"));
        assertThat(props.get("first")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsKey("anyOf");
        assertThat(JsonSchemas.forType(String.class)).isNull();
    }
}
