package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Tenant tables force row-level security even for their owner, and migrations run without a tenant. A data UPDATE or
 * DELETE in a migration therefore silently changes no rows unless its changeset lifts the forcing first (0045 failed
 * on a real database this way while passing on the empty test database). From 0045 on, every changeset that updates or
 * deletes rows must either lift it ("NO FORCE ROW LEVEL SECURITY") or say why it does not need to ("-- rls: ...").
 */
class MigrationRowSecurityTest {

    private static final Path CHANGES = Path.of("src/main/resources/db/changelog/changes");
    private static final Pattern NUMBER = Pattern.compile("^(\\d{4})-");
    private static final Pattern DATA_CHANGE = Pattern.compile("(?im)^\\s*(UPDATE|DELETE\\s+FROM)\\s+app\\.");

    @Test
    void dataChangesInMigrationsLiftForcedRowLevelSecurity() throws IOException {
        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.list(CHANGES)) {
            for (Path file : files.sorted().toList()) {
                Matcher n = NUMBER.matcher(file.getFileName().toString());
                if (!n.find() || Integer.parseInt(n.group(1)) < 45) {
                    continue; // earlier migrations are applied everywhere and cannot change
                }
                String[] changesets = Files.readString(file).split("(?m)^--changeset ");
                for (String changeset : changesets) {
                    if (DATA_CHANGE.matcher(changeset).find() && !changeset.contains("NO FORCE ROW LEVEL SECURITY")
                            && !changeset.contains("-- rls:")) {
                        offending.add(file.getFileName() + " :: " + changeset.lines().findFirst().orElse("?"));
                    }
                }
            }
        }
        assertThat(offending).as("changesets updating rows under forced row-level security").isEmpty();
    }
}
