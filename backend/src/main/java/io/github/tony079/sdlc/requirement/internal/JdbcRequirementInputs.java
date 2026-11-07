package io.github.tony079.sdlc.requirement.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.requirement.api.RequirementInputs;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JdbcRequirementInputs implements RequirementInputs {

    private final JdbcClient jdbc;

    JdbcRequirementInputs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RevisionInput> currentRevision(UUID requirementId) {
        return jdbc.sql("""
                SELECT r.id, r.project_id, r.created_by, v.revision_no, v.content_hash, v.kind, v.title, v.description,
                       v.business_objective, v.expected_outcomes, v.acceptance_criteria, v.repository_url, v.constraints,
                       v.integrations
                FROM app.requirement r
                JOIN app.requirement_revision v ON v.requirement_id = r.id AND v.revision_no = r.current_revision_no
                WHERE r.id = :id
                """).param("id", requirementId)
                .query((rs, n) -> new RevisionInput(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5),
                        new Content(rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14))))
                .optional();
    }
}
