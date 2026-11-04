package com.enterprise.iqk.agent.harness;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ActionSchemaRegistry 的纯单元测试，固化 architecture-agent-harness.md 的
 * 「Action schema」注册表：11 个 action、runtime 归属、trusted 分级与必填字段。
 * 「path plus content or patch」这类组合必填不在这里（由 ActionPolicyGuard 的
 * requiresPatchPayload 特例承载），只断言 path 必填、content/patch 可选。
 */
class ActionSchemaRegistryTest {

    private final ActionSchemaRegistry registry = new ActionSchemaRegistry();

    @Test
    void registersExactlyTheElevenDocumentedActions() {
        assertThat(registry.actions()).containsExactlyInAnyOrder(
                "query_school", "query_course", "add_course_reservation", "rag_search",
                "mcp_call",
                "workspace_list_files", "workspace_read_file", "workspace_search_text",
                "workspace_propose_patch", "workspace_apply_patch", "workspace_run_shell");
        assertThat(registry.list()).hasSameSizeAs(registry.actions());
    }

    @Test
    void finishIsNotARuntimeAction() {
        // finish 由 ReAct service 处理，代表循环结束，不注册为 runtime action
        assertThat(registry.find("finish")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void runtimeAndTrustFollowTheDocumentedTable() {
        Map<String, ActionSchema> schemas = registry.list().stream()
                .collect(Collectors.toMap(ActionSchema::action, Function.identity()));

        // builtin / default trust
        for (String action : Set.of("query_school", "query_course", "add_course_reservation", "rag_search")) {
            assertThat(schemas.get(action).runtime()).as(action).isEqualTo("builtin");
            assertThat(schemas.get(action).trustedOnly()).as(action).isFalse();
        }
        // mcp / trusted
        assertThat(schemas.get("mcp_call").runtime()).isEqualTo("mcp");
        // workspace / trusted
        for (String action : Set.of(
                "workspace_list_files", "workspace_read_file", "workspace_search_text",
                "workspace_propose_patch", "workspace_apply_patch", "workspace_run_shell")) {
            assertThat(schemas.get(action).runtime()).as(action).isEqualTo("workspace");
            assertThat(schemas.get(action).trustedOnly()).as(action).isTrue();
        }
    }

    @Test
    void requiredAndOptionalFieldsMatchTheDocumentedInputs() {
        assertThat(registry.find("query_school").orElseThrow().requiredFields()).isEmpty();
        assertThat(registry.find("rag_search").orElseThrow().requiredFields()).isEmpty();

        assertThat(registry.find("add_course_reservation").orElseThrow().requiredFields())
                .containsExactlyInAnyOrder("course", "studentName", "contactInfo", "school");
        assertThat(registry.find("mcp_call").orElseThrow().requiredFields())
                .containsExactlyInAnyOrder("server", "tool", "arguments");
        assertThat(registry.find("workspace_read_file").orElseThrow().requiredFields())
                .containsExactly("path");
        assertThat(registry.find("workspace_search_text").orElseThrow().requiredFields())
                .containsExactly("query");
        assertThat(registry.find("workspace_run_shell").orElseThrow().requiredFields())
                .containsExactly("command");

        // 文档的「path plus content or patch」：path 必填，content/patch 二选一在 policy guard 校验
        for (String action : Set.of("workspace_propose_patch", "workspace_apply_patch")) {
            ActionSchema schema = registry.find(action).orElseThrow();
            assertThat(schema.requiredFields()).containsExactly("path");
            assertThat(schema.optionalFields()).contains("content", "patch");
            assertThat(schema.sensitiveFields()).contains("content", "patch");
        }
    }

    @Test
    void knowsFieldCoversRequiredAndOptionalButNotSensitiveOnlyFields() {
        ActionSchema reservation = registry.find("add_course_reservation").orElseThrow();

        assertThat(reservation.knowsField("course")).isTrue();       // required
        assertThat(reservation.knowsField("remark")).isTrue();       // optional
        assertThat(reservation.knowsField("nonsense")).isFalse();
    }

    @Test
    void exposedCollectionsAreUnmodifiableCopies() {
        Set<String> actions = registry.actions();
        java.util.List<ActionSchema> list = registry.list();

        assertThatThrownBy(() -> actions.add("hijack"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> list.add(new ActionSchema("hijack", "builtin",
                Set.of(), Set.of(), Set.of(), "read", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
