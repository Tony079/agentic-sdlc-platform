package io.github.tony079.sdlc.modelgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import org.junit.jupiter.api.Test;

/** Provider resolution comes only from server configuration; misconfiguration fails at start-up. */
class ModelProviderRegistryTest {

    private final FakeModelProvider fake = new FakeModelProvider();

    @Test
    void resolvesTheConfiguredProvider() {
        assertThat(new ModelProviderRegistry(List.of(fake), "fake", "fake-deterministic-1").provider()).containsSame(fake);
        assertThat(new ModelProviderRegistry(List.of(fake), "none", "").provider()).isEmpty();
    }

    @Test
    void unsupportedProviderOrMissingModelFailsClearly() {
        assertThatThrownBy(() -> new ModelProviderRegistry(List.of(fake), "openai", "gpt"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'openai' is not supported");
        assertThatThrownBy(() -> new ModelProviderRegistry(List.of(fake), "fake", " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("model-id must be set");
    }

    /** The fake provider has no collaborators: it cannot reach the database, storage, jobs or tools. */
    @Test
    void fakeProviderHasNoDependencies() {
        assertThat(FakeModelProvider.class.getDeclaredConstructors()).allSatisfy(c -> assertThat(c.getParameterCount()).isZero());
        assertThat(Arrays.stream(FakeModelProvider.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).map(f -> f.getType().getName()))
                .allMatch(type -> type.startsWith("java."));
        assertThat(ModelProvider.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("name", "complete");
    }
}
