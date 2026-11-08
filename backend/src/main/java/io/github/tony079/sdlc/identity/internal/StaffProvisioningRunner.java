package io.github.tony079.sdlc.identity.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffProvisioning.Admin;
import io.github.tony079.sdlc.identity.internal.StaffProvisioning.ProvisioningException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Command line of the one-shot {@code provision} process (owner decision O-13). Usage:
 *
 * <pre>
 * create-staff --email=E --display-name=N [--enrolment-hours=72]          (password read from standard input)
 * bootstrap-admins --first=E --first-code=C --second=E --second-code=C     (development/test only)
 * bootstrap-ceremony-open --purpose=INITIAL|RECOVERY --first=E --second=E [--ttl-minutes=60]
 *                                                    (O-13; one-time authorisation read from standard input)
 * bootstrap-ceremony-approve --ceremony=ID --staff=E --code=C  (participant's own password from standard input)
 * grant-capability --staff=E --capability=REPOSITORY_REGISTER|STAFF_ADMIN --requester=E --requester-code=C
 *                  --approver=E --approver-code=C
 * assign-tenant --staff=E --tenant=SLUG --requester=E --requester-code=C --approver=E --approver-code=C
 * revoke-capability --staff=E --capability=X --admin=E --admin-code=C
 * revoke-assignment --staff=E --tenant=SLUG --admin=E --admin-code=C
 * disable-staff --staff=E --admin=E --admin-code=C
 * reset-mfa --staff=E --requester=E --requester-code=C --approver=E --approver-code=C [--enrolment-hours=72]
 * </pre>
 *
 * Only identifiers and outcomes are printed; passwords and codes never are.
 */
@Component
@Profile("provision")
class StaffProvisioningRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StaffProvisioningRunner.class);

    private final StaffProvisioning provisioning;

    StaffProvisioningRunner(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                            PasswordEncoder passwordEncoder, MfaCipher cipher,
                            @Value("${sdlc.provisioning.environment:}") String environment,
                            @Value("${sdlc.provisioning.production-bootstrap.enabled:false}") boolean ceremonyEnabled,
                            @Value("${sdlc.provisioning.production-bootstrap.authorization-sha256:}") String authorization) {
        this.provisioning = new StaffProvisioning(jdbc, transactionManager, passwordEncoder, cipher, environment,
                Clock.systemUTC(), ceremonyEnabled, authorization);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<String> commands = args.getNonOptionArgs();
        if (commands.size() != 1) {
            throw new ProvisioningException("exactly one provisioning command is required");
        }
        String command = commands.get(0);
        switch (command) {
            case "create-staff" -> {
                String password = readPassword();
                var id = provisioning.createStaff(one(args, "email"), one(args, "display-name"), password,
                        hours(args));
                LOG.info("Staff member created: {}", id);
            }
            case "bootstrap-admins" -> provisioning.bootstrapAdmins(admin(args, "first"), admin(args, "second"));
            case "bootstrap-ceremony-open" -> {
                String authorization = readPassword();
                List<String> ttl = args.getOptionValues("ttl-minutes");
                Duration lifetime = Duration.ofMinutes(ttl == null || ttl.isEmpty() ? 60 : Long.parseLong(ttl.get(0)));
                var id = provisioning.openCeremony(one(args, "purpose").toUpperCase(Locale.ROOT), one(args, "first"),
                        one(args, "second"), lifetime, authorization);
                LOG.info("Bootstrap ceremony opened: {}", id);
            }
            case "bootstrap-ceremony-approve" -> {
                String password = readPassword();
                LOG.info("Bootstrap ceremony: {}", provisioning.approveCeremony(
                        java.util.UUID.fromString(one(args, "ceremony")), one(args, "staff"), password,
                        one(args, "code")));
            }
            case "grant-capability" -> provisioning.grantCapability(one(args, "staff"), capability(args),
                    admin(args, "requester"), admin(args, "approver"));
            case "assign-tenant" -> provisioning.assignTenant(one(args, "staff"), one(args, "tenant"),
                    admin(args, "requester"), admin(args, "approver"));
            case "revoke-capability" -> provisioning.revokeCapability(one(args, "staff"), capability(args),
                    admin(args, "admin"));
            case "revoke-assignment" -> provisioning.revokeAssignment(one(args, "staff"), one(args, "tenant"),
                    admin(args, "admin"));
            case "disable-staff" -> provisioning.disableStaff(one(args, "staff"), admin(args, "admin"));
            case "reset-mfa" -> provisioning.resetMfa(one(args, "staff"), admin(args, "requester"),
                    admin(args, "approver"), hours(args));
            default -> throw new ProvisioningException("unknown provisioning command");
        }
        LOG.info("Provisioning command completed: {}", command);
    }

    private static String readPassword() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) {
            throw new ProvisioningException("the password must be supplied on standard input");
        }
        return line;
    }

    private static Admin admin(ApplicationArguments args, String role) {
        return new Admin(one(args, role), one(args, role + "-code"));
    }

    private static StaffCapability capability(ApplicationArguments args) {
        try {
            return StaffCapability.valueOf(one(args, "capability").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ProvisioningException("unknown capability");
        }
    }

    private static Duration hours(ApplicationArguments args) {
        List<String> values = args.getOptionValues("enrolment-hours");
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofHours(Long.parseLong(values.get(0)));
        } catch (NumberFormatException e) {
            throw new ProvisioningException("enrolment-hours must be a number");
        }
    }

    private static String one(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new ProvisioningException("--" + name + " is required exactly once");
        }
        return values.get(0);
    }
}
