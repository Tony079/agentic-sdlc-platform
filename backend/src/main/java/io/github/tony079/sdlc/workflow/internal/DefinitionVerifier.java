package io.github.tony079.sdlc.workflow.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses to start the api or worker if the compiled transition table differs from the stored workflow definition
 * R1-v1 (TDD 9.10 scope guard, test WF-D01): a definition with a state or transition the engine does not know, or
 * an engine rule the definition does not contain, stops the process before any request is served.
 */
@Component
@Profile("!migrate")
class DefinitionVerifier implements SmartInitializingSingleton {

    private static final TypeReference<List<Map<String, String>>> RULES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    DefinitionVerifier(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterSingletonsInstantiated() {
        String stored = tx.execute(status -> jdbc.sql("""
                SELECT transitions::text FROM app.workflow_definition WHERE version = :version AND status = 'ACTIVE'
                """).param("version", JdbcWorkflowEngine.DEFINITION_VERSION).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Workflow definition "
                        + JdbcWorkflowEngine.DEFINITION_VERSION + " is missing or not active")));
        verify(json.readValue(stored, RULES));
    }

    static void verify(List<Map<String, String>> stored) {
        Set<TransitionTable.Rule> fromDefinition = new HashSet<>();
        for (Map<String, String> rule : stored) {
            try {
                fromDefinition.add(new TransitionTable.Rule(Transition.valueOf(rule.get("transition")),
                        WorkflowState.valueOf(rule.get("from")), WorkflowState.valueOf(rule.get("to"))));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalStateException("Workflow definition contains an unknown transition or state: " + rule);
            }
        }
        if (!fromDefinition.equals(new HashSet<>(TransitionTable.RULES))) {
            throw new IllegalStateException("Workflow definition " + JdbcWorkflowEngine.DEFINITION_VERSION
                    + " does not match the engine's transition table");
        }
    }
}
