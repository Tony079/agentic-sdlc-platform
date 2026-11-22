package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.DevService.DevState;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** GD-36: the Development stage of a requirement. Identities come from the session only. */
@RestController
@Profile("!migrate & !provision")
class DevController {

    private static final String BASE = "/api/v1/requirements/{id}/development";

    private final DevService devs;

    DevController(DevService devs) {
        this.devs = devs;
    }

    @GetMapping(BASE)
    DevState state(@PathVariable UUID id) {
        return devs.state(id);
    }

    @PostMapping(BASE + "/start")
    DevState start(@PathVariable UUID id) {
        return devs.start(id);
    }

    @PostMapping(BASE + "/pause")
    DevState pause(@PathVariable UUID id) {
        return devs.pause(id, true);
    }

    @PostMapping(BASE + "/resume")
    DevState resume(@PathVariable UUID id) {
        return devs.pause(id, false);
    }

    @PostMapping(BASE + "/tasks/{taskId}/retry")
    DevState retry(@PathVariable UUID id, @PathVariable UUID taskId) {
        return devs.retry(id, taskId);
    }

    record BranchBody(String branch) {
    }

    /** GD-36: the branch development works from and opens pull requests into. */
    @PostMapping(BASE + "/base-branch")
    DevState baseBranch(@PathVariable UUID id, @RequestBody BranchBody body) {
        return devs.baseBranch(id, body == null ? null : body.branch());
    }

    record ModeBody(String mode) {
    }

    /** GD-36: one story at a time (SERIAL) or several at once (PARALLEL). */
    @PostMapping(BASE + "/mode")
    DevState mode(@PathVariable UUID id, @RequestBody ModeBody body) {
        return devs.mode(id, body == null ? null : body.mode());
    }

    /** One story's own log, session by session. */
    @GetMapping(BASE + "/tasks/{taskId}/log")
    List<ActivityLog.Entry> log(@PathVariable UUID id, @PathVariable UUID taskId) {
        return devs.log(id, taskId);
    }

    /** Outside work done: the waves waiting for it may start. */
    @PostMapping(BASE + "/tasks/{taskId}/done")
    DevState done(@PathVariable UUID id, @PathVariable UUID taskId) {
        return devs.markDone(id, taskId);
    }

    @PostMapping(BASE + "/tasks/{taskId}/skip")
    DevState skip(@PathVariable UUID id, @PathVariable UUID taskId) {
        return devs.skip(id, taskId);
    }
}
