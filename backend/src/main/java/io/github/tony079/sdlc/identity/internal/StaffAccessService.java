package io.github.tony079.sdlc.identity.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.api.StaffPrincipal;
import io.github.tony079.sdlc.identity.internal.StaffRepository.StaffRecord;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-checked staff authorisation, evaluated on every call (O-06): nothing is cached in the session. */
@Service
class StaffAccessService implements StaffAccess {

    private final StaffRepository staff;
    private final SecurityEventRecorder securityEvents;

    StaffAccessService(StaffRepository staff, SecurityEventRecorder securityEvents) {
        this.staff = staff;
        this.securityEvents = securityEvents;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StaffContext requireActiveTenant() {
        return check(null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StaffContext requireCapability(StaffCapability capability) {
        return check(capability);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireCapabilityForTenant(StaffCapability capability, UUID tenantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            throw deny(null, "NOT_STAFF");
        }
        UUID staffId = principal.staffId();
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() == null) {
            throw deny(staffId, "STAFF_INACTIVE");
        }
        if (tenantId == null || !staff.assignedAnyStatus(staffId, tenantId)) {
            throw deny(staffId, "NO_ASSIGNMENT");
        }
        if (!staff.hasCapability(staffId, capability)) {
            throw deny(staffId, "MISSING_" + capability.name());
        }
        return staffId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requirePlatformCapability(StaffCapability capability) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            throw deny(null, "NOT_STAFF");
        }
        UUID staffId = principal.staffId();
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() == null) {
            throw deny(staffId, "STAFF_INACTIVE");
        }
        if (!staff.hasCapability(staffId, capability)) {
            throw deny(staffId, "MISSING_" + capability.name());
        }
        return staffId;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requireAnyPlatformCapability(StaffCapability... capabilities) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            throw deny(null, "NOT_STAFF");
        }
        UUID staffId = principal.staffId();
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() == null) {
            throw deny(staffId, "STAFF_INACTIVE");
        }
        for (StaffCapability capability : capabilities) {
            if (staff.hasCapability(staffId, capability)) {
                return staffId;
            }
        }
        throw deny(staffId, "MISSING_CAPABILITY");
    }

    private StaffContext check(StaffCapability capability) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            throw deny(null, "NOT_STAFF");
        }
        UUID staffId = principal.staffId();
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() == null) {
            throw deny(staffId, "STAFF_INACTIVE");
        }
        UUID tenantId = principal.activeTenantId();
        if (tenantId == null || !staff.assigned(staffId, tenantId)) {
            throw deny(staffId, "NO_ACTIVE_ASSIGNMENT");
        }
        if (capability != null && !staff.hasCapability(staffId, capability)) {
            throw deny(staffId, "MISSING_" + capability.name());
        }
        return new StaffContext(staffId, tenantId);
    }

    private ApiException deny(UUID staffId, String reason) {
        securityEvents.record(SecurityEventType.STAFF_ACCESS_DENIED, Severity.MEDIUM,
                staffId == null ? null : staffId.toString(), reason);
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed.");
    }
}
