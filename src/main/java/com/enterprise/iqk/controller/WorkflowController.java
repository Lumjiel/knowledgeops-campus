package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowEventVO;
import com.enterprise.iqk.agent.workflow.WorkflowReactAgentService;
import com.enterprise.iqk.agent.workflow.WorkflowTaskVO;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * File: WorkflowController.java
 * <p>
 * Agent 工作流对外 REST 端点（前缀 {@code /ai/workflow}），对应
 * docs/architecture-agent-workflow.md 的「API 端点」：
 * <ul>
 *   <li>ReAct 工作流执行：同步 {@code POST /react/chat} 与 SSE 流式
 *       {@code POST /react/chat/stream}，委托 {@link WorkflowReactAgentService}；</li>
 *   <li>任务观测：任务详情（含步骤与事件）、事件流、租户任务列表，
 *       委托 {@link AgentWorkflowEngine} 的查询接口。</li>
 * </ul>
 * 观测类查询的租户隔离由引擎内部按 {@link TenantContext#currentTenantId()} 归一化处理。
 */
@Tag(name = "Agent Workflow", description = "多Agent工作流任务管理")
@RestController
@RequestMapping("/ai/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowReactAgentService workflowReactAgentService;
    private final AgentWorkflowEngine workflowEngine;

    /**
     * 同步执行一轮 ReAct 工作流：内部创建任务、迭代 planner 步骤并落库，
     * 返回最终答案与完整轨迹。
     *
     * @param request 包含 prompt（必填）与 chatId（必填）
     * @return 最终答案、轨迹步骤、引用/证据与路由元数据
     */
    @Operation(summary = "同步ReAct工作流")
    @PostMapping("/react/chat")
    public ResponseEntity<ReactChatResponseVO> reactChat(@RequestBody ReactChatRequestVO request) {
        return ResponseEntity.ok(workflowReactAgentService.chat(request));
    }

    /**
     * 流式执行 ReAct 工作流（SSE）：按 trace → token → done 顺序推送事件，
     * 失败时推送 error 事件而非错误信号（任务侧仍会标记 FAILED）。
     *
     * @param request 同 {@link #reactChat}
     * @return SSE 编码的事件流
     */
    @Operation(summary = "流式ReAct工作流 (SSE)")
    @PostMapping(value = "/react/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> reactChatStream(@RequestBody ReactChatRequestVO request) {
        return workflowReactAgentService.stream(request);
    }

    /**
     * 查询任务详情（含全部步骤与事件），按当前租户隔离。
     *
     * @return 200 任务详情 VO；任务不存在或不属于该租户时 404
     */
    @Operation(summary = "查询工作流任务详情")
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(@PathVariable String taskId) {
        WorkflowTaskVO task = workflowEngine.getTask(TenantContext.currentTenantId(), taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(Map.of("ok", 0, "msg", "task not found"));
        }
        return ResponseEntity.ok(task);
    }

    /**
     * 查询任务的事件流（按事件落库顺序），按当前租户隔离；
     * 任务不存在或不属于该租户时返回空列表。
     */
    @Operation(summary = "查询工作流任务事件列表")
    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<List<WorkflowEventVO>> getTaskEvents(@PathVariable String taskId) {
        return ResponseEntity.ok(workflowEngine.getTaskEvents(TenantContext.currentTenantId(), taskId));
    }

    /**
     * 分页查询当前租户的工作流任务列表（列表项含步骤、不含事件）。
     *
     * @param page     页码，从 1 起，默认 1
     * @param pageSize 每页条数，默认 20
     */
    @Operation(summary = "查询租户工作流任务列表")
    @GetMapping("/tasks")
    public ResponseEntity<List<WorkflowTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(workflowEngine.listTasks(TenantContext.currentTenantId(), page, pageSize));
    }
}
