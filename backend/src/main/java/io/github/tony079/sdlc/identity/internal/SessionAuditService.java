package io.github.tony079.sdlc.identity.internal;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audits login and logout of the current user in their tenant (PRD FR-AUD-03). */
@Service
class SessionAuditService {

    private final AuditWriter audit;

    SessionAuditService(AuditWriter audit) {
        this.audit = audit;
    }

    @Transactional
    public void recordLogin() {
        audit.record(AuditAction.LOGIN, "USER", TenantContext.currentActor().orElseThrow(), AuditResult.SUCCESS);
    }

    @Transactional
    public void recordLogout() {
        audit.record(AuditAction.LOGOUT, "USER", TenantContext.currentActor().orElseThrow(), AuditResult.SUCCESS);
    }
}
