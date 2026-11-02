package io.github.tony079.sdlc.common.tx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.tony079.sdlc.common.api.CorrelationId;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Applies the tenant, actor and correlation context to every database transaction (PRD FR-AUTH-17/18).
 *
 * <p>The values are set with {@code set_config(..., true)}, which is transaction-local: they vanish at
 * commit or rollback, so a pooled connection never carries one request's tenant into the next
 * (FR-AUTH-21). When there is no tenant the setting is the empty string, which {@code app.current_tenant()}
 * turns into NULL, so RLS policies match no rows (fail closed).
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    private static final String SET_CONTEXT = "SELECT set_config('app.tenant_id', ?, true), "
            + "set_config('app.actor_id', ?, true), set_config('app.correlation_id', ?, true)";

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        try (PreparedStatement statement = con.prepareStatement(SET_CONTEXT)) {
            statement.setString(1, TenantContext.currentTenant().map(UUID::toString).orElse(""));
            statement.setString(2, TenantContext.currentActor().map(UUID::toString).orElse(""));
            statement.setString(3, CorrelationId.current().orElse(""));
            statement.execute();
        }
    }
}
