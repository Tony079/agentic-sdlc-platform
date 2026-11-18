package io.github.tony079.sdlc.project.api;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A role in one project (Garuda decisions GD-02, GD-18, GD-27). A member may hold several roles (GD-27), except that
 * Viewer is held alone. The Environment Manager is not a role but a member named on the project.
 */
public enum ProjectRole {
    /** Reads the project's requirements, released BRDs, approved designs and status. */
    VIEWER,
    /** Creates requirements, uploads documents, answers clarifications and reviews the BRD of their own requirements. */
    REQUIREMENT_AUTHOR,
    /** Also approves BRDs, decides change requests, authorises repositories and sees restricted requirements. */
    BRD_APPROVER,
    /** GD-27: owns the Technical design stage: starts, reviews and approves designs; sees restricted requirements. */
    TECHNICAL_APPROVER;

    /** May create requirements and work on their own ones. */
    public static boolean canRequest(Collection<ProjectRole> roles) {
        return roles.contains(REQUIREMENT_AUTHOR) || roles.contains(BRD_APPROVER);
    }

    /** May take client-side BRD approval decisions in the project. */
    public static boolean canApprove(Collection<ProjectRole> roles) {
        return roles.contains(BRD_APPROVER);
    }

    /** Valid combination: one to four distinct roles; Viewer only on its own. */
    public static boolean valid(Collection<ProjectRole> roles) {
        if (roles == null || roles.isEmpty() || roles.stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        Set<ProjectRole> set = EnumSet.copyOf(roles);
        return set.size() == roles.size() && (!set.contains(VIEWER) || set.size() == 1);
    }

    /** In declaration order, without duplicates. */
    public static List<ProjectRole> sorted(Collection<ProjectRole> roles) {
        return roles == null || roles.isEmpty() ? List.of() : List.copyOf(EnumSet.copyOf(roles));
    }

    /** Reads a {@code text[]} column; null reads as no roles. */
    public static List<ProjectRole> fromSql(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        EnumSet<ProjectRole> set = EnumSet.noneOf(ProjectRole.class);
        for (Object value : (Object[]) array.getArray()) {
            set.add(ProjectRole.valueOf((String) value));
        }
        return List.copyOf(set);
    }

    /** Postgres array literal for binding as {@code :param::text[]}. */
    public static String toSql(Collection<ProjectRole> roles) {
        return "{" + String.join(",", sorted(roles).stream().map(Enum::name).toList()) + "}";
    }
}
