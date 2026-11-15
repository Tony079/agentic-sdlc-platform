package io.github.tony079.sdlc.billing.internal;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.billing.internal.BillingService.Decision;
import io.github.tony079.sdlc.billing.internal.BillingService.NewDecision;
import io.github.tony079.sdlc.billing.internal.BillingService.NewPlan;
import io.github.tony079.sdlc.billing.internal.BillingService.PendingProject;
import io.github.tony079.sdlc.billing.internal.BillingService.Plan;
import io.github.tony079.sdlc.billing.internal.BillingService.Report;
import io.github.tony079.sdlc.common.api.ApiException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Garuda billing (GD-19, GD-22): plans, project activation by two Garuda Admins, monthly report. */
@RestController
@ConditionalOnWebApplication
class BillingController {

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/api/v1/staff/billing/plans")
    List<Plan> plans() {
        return billing.plans();
    }

    @PostMapping("/api/v1/staff/billing/plans")
    @ResponseStatus(HttpStatus.CREATED)
    Plan propose(@RequestBody NewPlan body) {
        return billing.proposePlan(body);
    }

    @PostMapping("/api/v1/staff/billing/plans/{id}/{action}")
    Plan decidePlan(@PathVariable UUID id, @PathVariable String action) {
        return billing.decidePlan(id, action);
    }

    @GetMapping("/api/v1/staff/project-requests")
    List<PendingProject> pending() {
        return billing.pendingProjects();
    }

    @PostMapping("/api/v1/staff/project-requests")
    @ResponseStatus(HttpStatus.CREATED)
    Decision proposeDecision(@RequestBody NewDecision body) {
        return billing.proposeDecision(body);
    }

    @PostMapping("/api/v1/staff/project-decisions/{id}/{action}")
    Decision decide(@PathVariable UUID id, @PathVariable String action) {
        return billing.decide(id, action);
    }

    @GetMapping("/api/v1/staff/billing/report")
    Report report(@RequestParam String month) {
        return billing.report(month(month));
    }

    @GetMapping("/api/v1/staff/billing/report.csv")
    ResponseEntity<String> csv(@RequestParam String month) {
        YearMonth m = month(month);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"garuda-billing-" + m + ".csv\"")
                .contentType(new MediaType("text", "csv")).body(billing.reportCsv(m));
    }

    private static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.validation("Use a month like 2026-11.");
        }
    }
}
