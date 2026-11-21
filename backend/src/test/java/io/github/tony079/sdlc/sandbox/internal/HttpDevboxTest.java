package io.github.tony079.sdlc.sandbox.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.github.tony079.sdlc.sandbox.api.Devbox;
import io.github.tony079.sdlc.support.FakeGitHub;
import io.github.tony079.sdlc.support.TestDevbox;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

/** GD-36: the worker's client against the real devbox server: workspace, file tools, commands, changes, refusals. */
class HttpDevboxTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private Devbox devbox() {
        return new HttpDevbox(TestDevbox.get().url(), TestDevbox.get().token(), json);
    }

    @Test
    void aWorkspaceIsEditedRunAndItsChangesReturned() throws Exception {
        Devbox devbox = devbox();
        byte[] tarball = FakeGitHub.tarball("a".repeat(40), "acme-site-aaaaaaa", Map.of(
                "src/app.py", "print('hello')\n".getBytes(StandardCharsets.UTF_8),
                "README.md", "# Site\n".getBytes(StandardCharsets.UTF_8),
                ".gitignore", "out/\n".getBytes(StandardCharsets.UTF_8)));
        String id = devbox.create(new ByteArrayInputStream(tarball));
        try {
            assertThat(devbox.action(id, "list", Map.of("path", ""))).contains("src/app.py").contains("README.md");
            assertThat(devbox.action(id, "read", Map.of("path", "src/app.py"))).contains("hello");
            devbox.action(id, "write", Map.of("path", "src/app.py", "content", "print('bye')\n"));
            devbox.action(id, "write", Map.of("path", "tests/test_app.py", "content", "def test_ok():\n    assert True\n"));
            devbox.action(id, "write", Map.of("path", "out/generated.txt", "content", "ignored"));
            assertThat(devbox.action(id, "exec", Map.of("argv", List.of("python3", "src/app.py"))))
                    .contains("\"exitCode\":0").contains("bye");
            // Refusals come back as data for the model, not as failures.
            assertThat(devbox.action(id, "read", Map.of("path", "../../etc/passwd"))).contains("PATH_INVALID");
            assertThat(devbox.action(id, "exec", Map.of("argv", List.of("sh", "-c", "id"))))
                    .contains("COMMAND_NOT_ALLOWED");
            List<Devbox.ChangedFile> changes = devbox.changes(id);
            assertThat(changes).extracting(Devbox.ChangedFile::path)
                    .containsExactlyInAnyOrder("src/app.py", "tests/test_app.py");
            assertThat(new String(changes.stream().filter(c -> c.path().equals("src/app.py")).findFirst().orElseThrow()
                    .content(), StandardCharsets.UTF_8)).isEqualTo("print('bye')\n");
        } finally {
            devbox.delete(id);
        }
        assertThatThrownBy(() -> devbox.changes(id)).isInstanceOf(Devbox.DevboxException.class)
                .hasMessage("DEVBOX_WORKSPACE_GONE");
    }

    @Test
    void aWrongTokenAndAMissingConfigurationAreRefused() {
        Devbox wrong = new HttpDevbox(TestDevbox.get().url(), "w".repeat(40), json);
        assertThatThrownBy(() -> wrong.create(new ByteArrayInputStream(new byte[0])))
                .hasMessage("DEVBOX_UNAUTHORISED");
        Devbox none = new HttpDevbox("", "", json);
        assertThat(none.enabled()).isFalse();
        assertThatThrownBy(() -> none.changes("a".repeat(32))).hasMessage("DEVBOX_NOT_CONFIGURED");
    }
}
