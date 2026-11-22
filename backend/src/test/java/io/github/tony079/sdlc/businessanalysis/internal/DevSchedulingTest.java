package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.modelgateway.api.ModelTool;
import io.github.tony079.sdlc.sandbox.api.Devbox;

import org.junit.jupiter.api.Test;

/** GD-36: which stories of a wave start (one at a time or several), and how devbox refusals reach the model. */
class DevSchedulingTest {

    private static DevRepository.TaskRow task(String key, String status, boolean external, boolean draft) {
        return new DevRepository.TaskRow(UUID.randomUUID(), UUID.randomUUID(), key, key, 3, external, null, status, 1,
                null, null, null, null, null, null, null, null, null, null, null, draft);
    }

    private static List<String> keys(List<DevRepository.TaskRow> rows) {
        return rows.stream().map(DevRepository.TaskRow::storyKey).toList();
    }

    @Test
    void oneAtATimeWaitsForTheStoryBeforeToBeMerged() {
        // Nothing active: the first waiting story starts, only one.
        assertThat(keys(DevOrchestrator.toStart(true, 4, List.of(task("a", "WAITING", false, false),
                task("b", "WAITING", false, false), task("o", "WAITING", true, false))))).containsExactly("a");
        // A story in review, being coded, or holding unfinished work on a draft: nothing new starts.
        for (DevRepository.TaskRow blocker : List.of(task("a", "PR_OPEN", false, false), task("a", "CODING", false, false),
                task("a", "FAILED", false, true))) {
            assertThat(DevOrchestrator.toStart(true, 4, List.of(blocker, task("b", "WAITING", false, false)))).isEmpty();
        }
        // Merged, skipped, or failed without any work kept: the next one starts; a retried (queued) story goes first.
        assertThat(keys(DevOrchestrator.toStart(true, 4, List.of(task("a", "MERGED", false, false),
                task("b", "FAILED", false, false), task("c", "WAITING", false, false), task("d", "QUEUED", false, false)))))
                .containsExactly("d");
    }

    @Test
    void severalAtOnceFillTheFreeAgentSlots() {
        assertThat(keys(DevOrchestrator.toStart(false, 2, List.of(task("a", "CODING", false, false),
                task("b", "PR_OPEN", false, false), task("c", "WAITING", false, false), task("d", "WAITING", false, false)))))
                .containsExactly("c");
        assertThat(keys(DevOrchestrator.toStart(false, 2, List.of(task("a", "QUEUED", false, false),
                task("b", "QUEUED", false, false), task("c", "WAITING", false, false)))))
                .containsExactly("a", "b");
    }

    @Test
    void aDevboxRefusalTellsTheModelWhatToDo() {
        Devbox refusing = new Devbox() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String create(InputStream tarball) {
                return "w";
            }

            @Override
            public String action(String workspaceId, String action, Map<String, Object> arguments) {
                return "edit".equals(action) ? "{\"code\":\"EDIT_TEXT_NOT_FOUND\"}" : "{\"code\":\"COMMAND_NOT_ALLOWED\"}";
            }

            @Override
            public List<ChangedFile> changes(String workspaceId) {
                return List.of();
            }

            @Override
            public void delete(String workspaceId) {
            }
        };
        List<ModelTool> tools = DevTools.forWorkspace(refusing, "a".repeat(32));
        ModelTool edit = tools.stream().filter(t -> t.name().equals("edit_file")).findFirst().orElseThrow();
        assertThat(edit.parameters()).containsExactly("path", "old_text", "new_text");
        assertThatThrownBy(() -> edit.invoke(Map.of("path", "a.py", "old_text", "x", "new_text", "y")))
                .isInstanceOf(ModelTool.Refused.class).hasMessageContaining("copy the text exactly");
        ModelTool run = tools.stream().filter(t -> t.name().equals("run_command")).findFirst().orElseThrow();
        assertThatThrownBy(() -> run.invoke(Map.of("command", "sed -i s/a/b/ a.py")))
                .isInstanceOf(ModelTool.Refused.class).hasMessageContaining("use edit_file");
    }
}
