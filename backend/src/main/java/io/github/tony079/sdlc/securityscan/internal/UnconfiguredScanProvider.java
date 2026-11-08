package io.github.tony079.sdlc.securityscan.internal;

import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider;

/**
 * Placeholder for a scan kind with no configured provider (dependency vulnerabilities and SAST until O-03 names a
 * product). It never claims success: every run is recorded as UNAVAILABLE with PROVIDER_NOT_CONFIGURED, so a release
 * gate that requires this kind fails closed.
 */
class UnconfiguredScanProvider implements SecurityScanProvider {

    private final Kind kind;

    UnconfiguredScanProvider(Kind kind) {
        this.kind = kind;
    }

    @Override
    public String name() {
        return "unconfigured-" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String version() {
        return "none";
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public Result scan(Target target) {
        return Result.unavailable("PROVIDER_NOT_CONFIGURED");
    }
}
