package com.enterprise.iqk.agent.harness;

import com.enterprise.iqk.config.properties.AgentHarnessProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionPolicyGuardTest {

    private final ActionPolicyGuard guard = new ActionPolicyGuard(
            new ActionSchemaRegistry(), new AgentHarnessProperties());

    @Test
    void rejectsMissingRequiredFields() {
        AgentAction action = new AgentAction("add_course_reservation",
                Map.of("course", "Java"), "prompt", "tenant", "chat", "balanced", "", "");

        ActionPolicyDecision decision = guard.evaluate(action);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("invalid_action_input");
        assertThat(decision.message()).contains("studentName", "contactInfo", "school");
    }

    @Test
    void rejectsTrustedRuntimeActionsWithoutTrustedFlag() {
        AgentAction action = new AgentAction("workspace_read_file",
                Map.of("path", "README.md"), "prompt", "tenant", "chat", "balanced", "", "");

        ActionPolicyDecision decision = guard.evaluate(action);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("trusted_runtime_required");
    }

    @Test
    void allowsTrustedRuntimeActionsWithValidInput() {
        AgentAction action = new AgentAction("mcp_call",
                Map.of("server", "demo", "tool", "echo", "arguments", Map.of("text", "hi")),
                "prompt", "tenant", "chat", "balanced", "task-1", "step-1", true);

        ActionPolicyDecision decision = guard.evaluate(action);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.schema().runtime()).isEqualTo("mcp");
        assertThat(decision.schema().riskLevel()).isEqualTo("external");
    }

    @Test
    void rejectsUnknownFields() {
        AgentAction action = new AgentAction("query_course",
                Map.of("type", "Java", "extra", "x"), "prompt", "tenant", "chat", "balanced", "", "");

        ActionPolicyDecision decision = guard.evaluate(action);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.message()).contains("unknown field(s): extra");
    }

    @Test
    void rejectsDisabledActionsFromConfiguration() {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getDisabledActions().add("rag_search");
        ActionPolicyGuard localGuard = new ActionPolicyGuard(new ActionSchemaRegistry(), properties);

        ActionPolicyDecision decision = localGuard.evaluate(new AgentAction("rag_search",
                Map.of("query", "x"), "prompt", "tenant", "chat", "balanced", "", ""));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("disabled_action");
    }

    @Test
    void rejectsTenantDeniedActionsFromConfiguration() {
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.getTenantAllowedActions().put("tenant-a", java.util.Set.of("query_school"));
        ActionPolicyGuard localGuard = new ActionPolicyGuard(new ActionSchemaRegistry(), properties);

        ActionPolicyDecision decision = localGuard.evaluate(new AgentAction("rag_search",
                Map.of("query", "x"), "prompt", "tenant-a", "chat", "balanced", "", ""));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("tenant_action_denied");
    }

    @Test
    void rejectsTrustedActionsWhenTrustedRuntimeIsDisabled() {
        // 生产 profile 的默认边界：trusted runtime 关闭后，即使 action 带上
        // trustedRuntimeAccess=true 也会在 runtime 之前被拒
        AgentHarnessProperties properties = new AgentHarnessProperties();
        properties.setTrustedRuntimeEnabled(false);
        ActionPolicyGuard localGuard = new ActionPolicyGuard(new ActionSchemaRegistry(), properties);

        ActionPolicyDecision decision = localGuard.evaluate(new AgentAction("workspace_read_file",
                Map.of("path", "README.md"), "prompt", "tenant", "chat", "balanced", "", "", true));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("trusted_runtime_disabled");
    }

    @Test
    void allowsWorkspacePatchActionsWithEitherContentOrPatch() {
        // 文档「path plus content or patch」：二选一通过，两个都缺才拒绝
        ActionPolicyDecision withContent = guard.evaluate(new AgentAction("workspace_propose_patch",
                Map.of("path", "README.md", "content", "next"), "prompt", "tenant", "chat",
                "balanced", "", "", true));
        ActionPolicyDecision withPatch = guard.evaluate(new AgentAction("workspace_propose_patch",
                Map.of("path", "README.md", "patch", "--- a/README.md\n+++ b/README.md\n"), "prompt",
                "tenant", "chat", "balanced", "", "", true));
        ActionPolicyDecision withNeither = guard.evaluate(new AgentAction("workspace_propose_patch",
                Map.of("path", "README.md", "summary", "noop"), "prompt", "tenant", "chat",
                "balanced", "", "", true));

        assertThat(withContent.allowed()).isTrue();
        assertThat(withPatch.allowed()).isTrue();
        assertThat(withNeither.allowed()).isFalse();
        assertThat(withNeither.code()).isEqualTo("invalid_action_input");
        assertThat(withNeither.message()).contains("content or patch");
    }
}
