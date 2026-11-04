package com.enterprise.iqk.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorkflowState 状态机的穷举测试，固化 architecture-agent-workflow.md 的
 * mermaid 状态图：枚举 {@code canTransitionTo} 的 switch 与文档图逐边一致。
 *
 * <p>测试 1 是全量笛卡尔积（10×10=100 对）：凡是文档图里没有的边一律非法，
 * 防止未来给枚举加边时与架构文档漂移而不被发现。
 */
class WorkflowStateTest {

    /** 与 mermaid 图逐边对应；终态 DONE/FAILED 无出边。 */
    private static final Map<WorkflowState, Set<WorkflowState>> DOCUMENTED_EDGES = Map.of(
            WorkflowState.CREATED, Set.of(WorkflowState.PLANNING),
            WorkflowState.PLANNING, Set.of(WorkflowState.SEARCHING, WorkflowState.RETRIEVING,
                    WorkflowState.WRITING, WorkflowState.FAILED),
            WorkflowState.SEARCHING, Set.of(WorkflowState.RETRIEVING, WorkflowState.JUDGING,
                    WorkflowState.FAILED),
            WorkflowState.RETRIEVING, Set.of(WorkflowState.JUDGING, WorkflowState.REFLECTING,
                    WorkflowState.FAILED),
            WorkflowState.JUDGING, Set.of(WorkflowState.REFLECTING, WorkflowState.WRITING,
                    WorkflowState.FAILED),
            WorkflowState.REFLECTING, Set.of(WorkflowState.WRITING, WorkflowState.NEED_MORE_EVIDENCE,
                    WorkflowState.FAILED),
            WorkflowState.NEED_MORE_EVIDENCE, Set.of(WorkflowState.SEARCHING, WorkflowState.RETRIEVING,
                    WorkflowState.FAILED),
            WorkflowState.WRITING, Set.of(WorkflowState.DONE, WorkflowState.FAILED),
            WorkflowState.DONE, Set.of(),
            WorkflowState.FAILED, Set.of());

    @Test
    void canTransitionToMatchesTheDocumentedStateDiagramExactly() {
        List<String> mismatches = new ArrayList<>();
        for (WorkflowState from : WorkflowState.values()) {
            for (WorkflowState to : WorkflowState.values()) {
                boolean allowed = from.canTransitionTo(to);
                boolean documented = DOCUMENTED_EDGES.get(from).contains(to);
                if (allowed != documented) {
                    mismatches.add(from + " -> " + to + " (allowed=" + allowed + ")");
                }
            }
        }
        assertThat(mismatches)
                .as("枚举允许集与文档状态图的差异，共 %d 个状态", WorkflowState.values().length)
                .isEmpty();
    }

    @Test
    void happyPathTraversesPlanningRetrievalAndReflectionLoop() {
        // 文档主链：含 NEED_MORE_EVIDENCE 回到 SEARCHING 的反思循环，再收口 DONE
        List<WorkflowState> path = List.of(
                WorkflowState.CREATED,
                WorkflowState.PLANNING,
                WorkflowState.RETRIEVING,
                WorkflowState.JUDGING,
                WorkflowState.REFLECTING,
                WorkflowState.NEED_MORE_EVIDENCE,
                WorkflowState.SEARCHING,
                WorkflowState.JUDGING,
                WorkflowState.WRITING,
                WorkflowState.DONE);

        for (int i = 0; i < path.size() - 1; i++) {
            assertThat(path.get(i).canTransitionTo(path.get(i + 1)))
                    .as("%s -> %s", path.get(i), path.get(i + 1))
                    .isTrue();
        }
    }

    @Test
    void everyNonTerminalStateExceptCreatedCanFailDirectly() {
        // FAILED 是全图兜底：除 CREATED/终态自身外，其余状态都可直达 FAILED
        for (WorkflowState state : WorkflowState.values()) {
            if (state == WorkflowState.CREATED || state.isTerminal()) {
                assertThat(state.canTransitionTo(WorkflowState.FAILED)).as(String.valueOf(state)).isFalse();
            } else {
                assertThat(state.canTransitionTo(WorkflowState.FAILED)).as(String.valueOf(state)).isTrue();
            }
        }
    }

    @Test
    void terminalStatesAreAbsorbingAndOnlyDoneFailedAreTerminal() {
        for (WorkflowState state : WorkflowState.values()) {
            assertThat(state.isTerminal()).as(String.valueOf(state))
                    .isEqualTo(state == WorkflowState.DONE || state == WorkflowState.FAILED);
            if (state.isTerminal()) {
                // 终态（含自转换）一律拒绝，配合引擎 updateStatus 的 0 行防线兜底
                assertThat(state.canTransitionTo(WorkflowState.DONE)).as(String.valueOf(state)).isFalse();
                assertThat(state.canTransitionTo(WorkflowState.FAILED)).as(String.valueOf(state)).isFalse();
                assertThat(state.canTransitionTo(state)).as(String.valueOf(state)).isFalse();
            }
        }
    }

    @Test
    void selfTransitionsAndDocumentedShortcutsAreForbidden() {
        for (WorkflowState state : WorkflowState.values()) {
            assertThat(state.canTransitionTo(state)).as(String.valueOf(state)).isFalse();
        }
        // 典型非法跳步：不允许绕过中间阶段
        assertThat(WorkflowState.CREATED.canTransitionTo(WorkflowState.DONE)).isFalse();
        assertThat(WorkflowState.PLANNING.canTransitionTo(WorkflowState.JUDGING)).isFalse();
        assertThat(WorkflowState.JUDGING.canTransitionTo(WorkflowState.SEARCHING)).isFalse();
        assertThat(WorkflowState.NEED_MORE_EVIDENCE.canTransitionTo(WorkflowState.DONE)).isFalse();
        assertThat(WorkflowState.RETRIEVING.canTransitionTo(WorkflowState.WRITING)).isFalse();
    }
}
