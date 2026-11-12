package io.github.tony079.sdlc.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeInfoControllerTest {

    @Test
    void developmentAdaptersAreNamed() {
        var info = new RuntimeInfoController(" development-mock ", "development").runtime();
        assertThat(info.modelProvider()).isEqualTo("development-mock");
        assertThat(info.developmentAdapters()).containsExactly("MODEL", "REPOSITORY");
        assertThat(new RuntimeInfoController("anthropic", "github").runtime().developmentAdapters()).isEmpty();
    }
}
