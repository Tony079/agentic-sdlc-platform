package io.github.tony079.sdlc.platform.internal;

import java.util.UUID;

import io.github.tony079.sdlc.platform.internal.PlatformDashboardService.OrganisationDetail;
import io.github.tony079.sdlc.platform.internal.PlatformDashboardService.Overview;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Garuda dashboard (FR-DSH-01..03) for staff with ORG_ONBOARD, STAFF_ADMIN or AUDITOR. */
@RestController
@ConditionalOnWebApplication
class PlatformDashboardController {

    private final PlatformDashboardService dashboard;

    PlatformDashboardController(PlatformDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/staff/platform/dashboard")
    Overview overview() {
        return dashboard.overview();
    }

    @GetMapping("/api/v1/staff/platform/organisations/{id}")
    OrganisationDetail organisation(@PathVariable UUID id) {
        return dashboard.organisation(id);
    }
}
