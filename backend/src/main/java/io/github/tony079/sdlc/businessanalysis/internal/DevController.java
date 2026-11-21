package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.DevService.DevState;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping(BASE + "/tasks/{taskId}/skip")
    DevState skip(@PathVariable UUID id, @PathVariable UUID taskId) {
        return devs.skip(id, taskId);
    }
}
