package com.enterprise.iqk.service;

import com.enterprise.iqk.agent.harness.AgentAction;
import com.enterprise.iqk.agent.harness.AgentHarnessService;
import com.enterprise.iqk.agent.harness.AgentObservation;
import com.enterprise.iqk.domain.vo.ReactChatRequestVO;
import com.enterprise.iqk.domain.vo.ReactChatResponseVO;
import com.enterprise.iqk.domain.vo.ReactTraceStepVO;
import com.enterprise.iqk.llm.ModelRouter;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.ConversationIdHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.enterprise.iqk.service.ReactDecisionParser.ReasonDecision;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * ReAct 智能体服务（Reason + Act 循环），提供阻塞式与 SSE 流式两种对话入口。
 *
 * <p>每轮对话最多执行 {@value #MAX_STEPS} 个规划步：LLM 规划器返回 JSON 决策，
 * 所选动作经 {@link AgentHarnessService} 执行（工具策略与租户隔离在此层强制），
 * 观察结果折叠进 rolling context 供下一步规划使用。决策解析与响应装配分别
 * 委托 {@link ReactDecisionParser} 与 {@link ReactResponseFormatter}。</p>
 *
 * <p>与工作流版服务（WorkflowReactAgentService）的区别：本类不持久化
 * task/step 记录，模型调用带 ChatMemory 会话（见 {@link #conversationId}），
 * 并对"决策 JSON 被当作最终答案"的模型异常输出做消毒（见
 * {@link #chat} 与 {@link #stream} 内的提取逻辑）。</p>
 *
 * <p>失败策略为降级不失败：规划失败回退关键词兜底决策，摘要失败回退固定
 * 文案，并通过 fallback 标记在响应中如实上报。租户预算在每次模型调用前
 * 经 {@link TenantCostService} 强制校验，流式指标按结果打点。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReactAgentService {
    /** 每次请求的 ReAct 规划步数上限，超限后直接进入答案汇总阶段。 */
    private static final int MAX_STEPS = 4;

    /** 单轮 ReAct 规划循环的墙钟预算；超时后停止规划并降级为答案汇总，避免慢工具/慢模型无限拉长请求。 */
    private static final long LOOP_DEADLINE_MS = 50_000L;

    /** 流式最终答案的信号间空闲超时：模型流静默超过该时长视为流失败，转为 error 事件。 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(60);

    /** rolling context 折叠进规划/汇总 prompt 的长度上限，超限截掉最旧观察，防止 prompt 无界膨胀。 */
    private static final int MAX_ROLLING_CONTEXT_CHARS = 12_000;

    /** 汇总失败/空流时的兜底答案文案。 */
    private static final String CANNED_FALLBACK_ANSWER = "当前未能生成最终答案，请稍后重试。";

    private final AgentHarnessService agentHarnessService;
    private final ChatClient chatClient;
    private final ModelRouter modelRouter;
    private final TenantCostService tenantCostService;
    private final MeterRegistry meterRegistry;
    private final ReactDecisionParser decisionParser;
    private final ReactResponseFormatter responseFormatter;

    /**
     * 执行一次阻塞式 ReAct 对话。
     *
     * <p>循环最多 {@value #MAX_STEPS} 步：规划器判定 {@code finish} 且给出
     * 非空答案时直接采用（先做决策 JSON 消毒），否则调用摘要模型生成最终
     * 答案；步数耗尽同样走摘要路径。任何一步产生的 fallback 标记会聚合到
     * 最终响应。</p>
     *
     * @param request 提示词、chatId 与可选模型档位；prompt 与 chatId 必填
     * @return 最终答案（含引用脚注）与轨迹步骤、路由/实验元数据、fallback 标记
     * @throws IllegalArgumentException prompt 或 chatId 为空时抛出
     */
    public ReactChatResponseVO chat(ReactChatRequestVO request) {
        validateRequest(request);
        String tenantId = currentTenantId();
        long startedNs = System.nanoTime();
        ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                request.getModelProfile(),
                "react",
                request.getChatId(),
                tenantId
        );
        if (routeDecision == null) {
            throw new IllegalStateException("model router returned no route decision for REACT turn"
                    + " (chatId=" + request.getChatId() + ", tenantId=" + tenantId + ")");
        }

        List<ReactTraceStepVO> trace = new ArrayList<>();
        String rollingContext = "";
        boolean usedFallback = false;

        for (int step = 1; step <= MAX_STEPS; step++) {
            if (step > 1 && elapsedMs(startedNs) > LOOP_DEADLINE_MS) {
                log.warn("[react] loop deadline exceeded (chatId={}, completedSteps={}, elapsed={}ms);"
                                + " degrading to summarization instead of planning further",
                        request.getChatId(), step - 1, elapsedMs(startedNs));
                break;
            }
            ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);
            usedFallback = usedFallback || decision.fallback();

            if ("finish".equals(decision.action())) {
                AnswerResult raw = StringUtils.hasText(decision.answer())
                        ? new AnswerResult(decision.answer(), false)
                        : summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
                // 消毒：若最终答案疑似决策 JSON，提取 answer 字段
                String sanitizedAnswer = raw.answer() != null && raw.answer().stripLeading().startsWith("{")
                        ? java.util.Optional.ofNullable(decisionParser.extractAnswerFromRaw(raw.answer()))
                                .filter(java.util.function.Predicate.not(String::isBlank))
                                .orElse("（模型输出异常，请重试）")
                        : raw.answer();
                AnswerResult answer = new AnswerResult(sanitizedAnswer, raw.fallback());
                usedFallback = usedFallback || answer.fallback();
                Map<String, Object> observation = new LinkedHashMap<>();
                observation.put("status", "completed");
                observation.put("fallback", usedFallback);
                if (decision.citations() != null && !decision.citations().isEmpty()) {
                    observation.put("citations", decision.citations());
                }
                if (decision.evidence() != null && !decision.evidence().isEmpty()) {
                    observation.put("evidence", decision.evidence());
                }
                trace.add(ReactTraceStepVO.builder()
                        .step(step)
                        .thought(decision.thought())
                        .action("finish")
                        .actionInput(decision.actionInput())
                        .observation(observation)
                        .build());
                return responseFormatter.success(request.getChatId(), answer.answer(), trace, routeDecision, usedFallback);
            }

            Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId);
            trace.add(ReactTraceStepVO.builder()
                    .step(step)
                    .thought(decision.thought())
                    .action(decision.action())
                    .actionInput(decision.actionInput())
                    .observation(observation)
                    .build());

            rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
        }

        AnswerResult answer = summarizeAnswer(request, trace, rollingContext, routeDecision, tenantId);
        return responseFormatter.success(request.getChatId(), answer.answer(), trace, routeDecision, usedFallback || answer.fallback());
    }

    /**
     * 执行流式 ReAct 对话，输出 SSE 事件序列：每个执行步一条 {@code trace}
     * 事件，随后为最终答案的 {@code token} 事件流，最后一条 {@code done}
     * 事件携带完整 {@link ReactChatResponseVO}；失败时以 {@code error}
     * 事件收尾（不向外抛出 reactor 错误信号）。
     *
     * <p>所有副作用延迟到订阅时执行。规划器给出完整答案时按 24 字符分段、
     * 14ms 间隔模拟流式下发（见 {@link #splitForStreaming}）；首 token 若以
     * {@code {} 开头则缓冲全量并提取 {@code answer} 字段后再下发，防止决策
     * JSON 泄漏给用户。总时延/首 token 时延/请求计数在 {@code doFinally}
     * 中按结果打点。</p>
     *
     * @param request 提示词、chatId 与可选模型档位；校验在订阅时进行
     * @return 按上述格式编码的 SSE 字符串流
     */
    public Flux<String> stream(ReactChatRequestVO request) {
        long startedNs = System.nanoTime();
        AtomicReference<Long> firstTokenLatencyMsRef = new AtomicReference<>(null);
        AtomicReference<String> outcomeRef = new AtomicReference<>("error");

        return Flux.defer(() -> {
                    validateRequest(request);
                    String tenantId = currentTenantId();
                    ModelRouter.ModelRouteDecision routeDecision = resolveRouteDecision(
                            request.getModelProfile(),
                            "react",
                            request.getChatId(),
                            tenantId
                    );
                    if (routeDecision == null) {
                        throw new IllegalStateException("model router returned no route decision for REACT_STREAM turn"
                                + " (chatId=" + request.getChatId() + ", tenantId=" + tenantId + ")");
                    }

                    List<ReactTraceStepVO> trace = new ArrayList<>();
                    String rollingContext = "";
                    String directAnswer = "";
                    boolean usedFallback = false;

                    for (int step = 1; step <= MAX_STEPS; step++) {
                        if (step > 1 && elapsedMs(startedNs) > LOOP_DEADLINE_MS) {
                            log.warn("[react] stream loop deadline exceeded (chatId={}, completedSteps={}, elapsed={}ms);"
                                            + " skipping to final answer",
                                    request.getChatId(), step - 1, elapsedMs(startedNs));
                            break;
                        }
                        ReasonDecision decision = reason(request, rollingContext, trace, routeDecision, tenantId);
                        usedFallback = usedFallback || decision.fallback();
                        if ("finish".equals(decision.action())) {
                            Map<String, Object> observation = new LinkedHashMap<>();
                            observation.put("status", "completed");
                            observation.put("fallback", usedFallback);
                            if (decision.citations() != null && !decision.citations().isEmpty()) {
                                observation.put("citations", decision.citations());
                            }
                            if (decision.evidence() != null && !decision.evidence().isEmpty()) {
                                observation.put("evidence", decision.evidence());
                            }
                            trace.add(ReactTraceStepVO.builder()
                                    .step(step)
                                    .thought(decision.thought())
                                    .action("finish")
                                    .actionInput(decision.actionInput())
                                    .observation(observation)
                                    .build());
                            directAnswer = emptyIfBlank(decision.answer());
                            break;
                        }

                        Object observation = executeAction(request, decision.action(), decision.actionInput(), tenantId);
                        trace.add(ReactTraceStepVO.builder()
                                .step(step)
                                .thought(decision.thought())
                                .action(decision.action())
                                .actionInput(decision.actionInput())
                                .observation(observation)
                                .build());
                        rollingContext = responseFormatter.appendContext(rollingContext, decision.action(), observation);
                    }

                    boolean responseUsedFallback = usedFallback;

                    Flux<String> traceFlux = Flux.fromIterable(trace)
                            .map(step -> responseFormatter.formatSse("trace", responseFormatter.toJson(step)));
                    StringBuilder answerBuilder = new StringBuilder();

                    Flux<String> answerSourceFlux = StringUtils.hasText(directAnswer)
                            ? Flux.fromIterable(splitForStreaming(directAnswer))
                                    .delayElements(java.time.Duration.ofMillis(14))
                            : callModelStream(
                            "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                            buildFinalPrompt(request, trace, rollingContext),
                            routeDecision,
                            tenantId,
                            "react_final",
                            conversationId(request)
                    )
                            // 模型流静默（信号间无 token）超过上限即失败，避免 SSE 连接被
                            // 无响应的上游无限挂住；超时异常经 onErrorResume 转为 error 事件。
                            .timeout(STREAM_IDLE_TIMEOUT);

                    // 防泄漏：最终回答若以 JSON 开头（cheap 模型偶发把决策 JSON 当答案），
                    // 缓冲全量后提取 answer 字段再下发，其余情况保持逐 token 流式。
                    Flux<String> sanitizedAnswerFlux = answerSourceFlux
                            .switchOnFirst((signal, flux) -> {
                                if (signal.hasValue()) {
                                    Object first = signal.get();
                                    if (first != null && first.toString().stripLeading().startsWith("{")) {
                                        return flux.collectList()
                                                .map(list -> {
                                                    String whole = String.join("", list);
                                                    String extracted = decisionParser.extractAnswerFromRaw(whole);
                                                    answerBuilder.setLength(0);
                                                    answerBuilder.append(extracted);
                                                    return StringUtils.hasText(extracted)
                                                            ? extracted
                                                            : "（模型输出异常，请重试）";
                                                })
                                                .flux();
                                    }
                                }
                                return flux;
                            });

                    Flux<String> tokenFlux = sanitizedAnswerFlux
                            .map(token -> {
                                if (firstTokenLatencyMsRef.get() == null) {
                                    firstTokenLatencyMsRef.set(elapsedMs(startedNs));
                                }
                                answerBuilder.append(token);
                                return responseFormatter.formatSse("token", responseFormatter.toJson(Map.of("token", token)));
                            });

                    return Flux.concat(traceFlux, tokenFlux)
                            .concatWith(Flux.defer(() -> {
                                if (firstTokenLatencyMsRef.get() == null) {
                                    firstTokenLatencyMsRef.set(elapsedMs(startedNs));
                                }
                                // 部分失败防护：模型流整体为空时不能以空答案收 DONE，
                                // 降级为兜底文案并在响应中如实标记 fallback。
                                boolean emptyAnswer = !StringUtils.hasText(answerBuilder.toString());
                                String finalAnswer = emptyAnswer ? CANNED_FALLBACK_ANSWER : answerBuilder.toString();
                                ReactChatResponseVO response = responseFormatter.success(
                                        request.getChatId(), finalAnswer, trace, routeDecision,
                                        responseUsedFallback || emptyAnswer);
                                outcomeRef.set("success");
                                return Flux.just(responseFormatter.formatSse("done", responseFormatter.toJson(response)));
                            }));
                })
                .onErrorResume(ex -> {
                    // getMessage() 为空（如 NPE）时带上异常类名，error 事件里能看出原因类型。
                    String message = StringUtils.hasText(ex.getMessage())
                            ? ex.getMessage()
                            : ex.getClass().getSimpleName() + ": stream failed";
                    return Flux.just(responseFormatter.formatSse("error", responseFormatter.toJson(Map.of("message", message))));
                })
                .doFinally(signal -> recordStreamMetrics(startedNs, firstTokenLatencyMsRef.get(), outcomeRef.get()));
    }

    /** 以纳秒起点计算已流逝毫秒数，供时延打点使用。 */
    private long elapsedMs(long startedNs) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    }

    /**
     * 按最终结果（success/error）记录流式指标：总时延与首 token 时延
     * （直方图 Timer，仅在观察到 token 时记录后者）及请求计数 Counter。
     */
    private void recordStreamMetrics(long startedNs, Long firstTokenLatencyMs, String outcome) {
        long totalLatencyMs = elapsedMs(startedNs);
        Timer.builder("react.stream.total.latency")
                .description("End-to-end latency for /ai/react/chat/stream")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(totalLatencyMs, TimeUnit.MILLISECONDS);

        if (firstTokenLatencyMs != null) {
            Timer.builder("react.stream.first_token.latency")
                    .description("Time-to-first-token latency for /ai/react/chat/stream")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(firstTokenLatencyMs, TimeUnit.MILLISECONDS);
        }

        Counter.builder("react.stream.requests")
                .description("Total streamed ReAct requests")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** 把 planner 直接给出的完整答案切成小段延迟下发，保持流式观感。 */
    private List<String> splitForStreaming(String text) {
        List<String> parts = new ArrayList<>();
        int size = 24;
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    /**
     * 单个规划步：要求模型以严格 JSON 返回 ReAct 决策（thought/action/
     * action_input/answer），动作限定在五个允许项内。模型调用或解析失败时
     * 记录告警并回退 {@link ReactDecisionParser#fallback} 的确定性决策，
     * 保证循环始终可推进。
     */
    private ReasonDecision reason(ReactChatRequestVO request,
                                  String rollingContext,
                                  List<ReactTraceStepVO> trace,
                                  ModelRouter.ModelRouteDecision routeDecision,
                                  String tenantId) {
        String planningPrompt = """
                You are a ReAct planner for an education assistant.
                You must choose exactly one action for the next step.
                Always write "thought" and any user-visible text in Chinese (Simplified).
                %n
                Allowed actions:
                - query_school
                - query_course
                - add_course_reservation
                - rag_search
                - finish
                %n
                Return JSON only:
                {
                  "thought": "short reasoning",
                  "action": "one action from list",
                  "action_input": {"key":"value"},
                  "answer": "only provide when action is finish"
                }
                %n
                User question:
                %s
                %n
                Rolling context:
                %s
                %n
                Existing trace:
                %s%n""".formatted(
                request.getPrompt(),
                capContext(rollingContext),
                responseFormatter.toJson(trace)
        );

        try {
            String raw = callModel(
                    "You are strict JSON ReAct planner. Return valid JSON only.",
                    planningPrompt,
                    routeDecision,
                    tenantId,
                    "react_planner",
                    conversationId(request)
            );
            return decisionParser.parse(raw);
        } catch (RuntimeException ex) {
            log.warn("[react] planner failed: {}", ex.toString(), ex);
            return decisionParser.fallback(request.getPrompt());
        }
    }

    /**
     * 将规划器选定的动作分发给 {@link AgentHarnessService} 执行并返回观察
     * 结果（Map 形式，用于轨迹与 rolling context）。本服务不做工作流持久化，
     * 故 taskId/stepId 传空串。
     */
    private Map<String, Object> executeAction(ReactChatRequestVO request,
                                              String action,
                                              Map<String, Object> actionInput,
                                              String tenantId) {
        AgentObservation observation = agentHarnessService.execute(new AgentAction(
                action,
                actionInput,
                request.getPrompt(),
                tenantId,
                request.getChatId(),
                request.getModelProfile(),
                "",
                ""
        ));
        if (observation == null) {
            // 工具门面正常情况下总返回 observation；返回 null 说明实现异常，
            // 给出带动作名的明确错误而不是裸 NPE。
            throw new IllegalStateException("tool harness returned no observation for action '" + action
                    + "' (chatId=" + request.getChatId() + ")");
        }
        Map<String, Object> result = observation.toMap();
        return result != null ? result : Map.of();
    }

    /**
     * 基于已积累轨迹与 rolling context 调用模型生成最终答案；调用失败或返回
     * 空白时降级为固定提示文案，并标记 fallback=true 如实上报。
     */
    private AnswerResult summarizeAnswer(ReactChatRequestVO request,
                                         List<ReactTraceStepVO> trace,
                                         String rollingContext,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId) {
        String finalPrompt = buildFinalPrompt(request, trace, rollingContext);
        try {
            String answer = callModel(
                    "你是企业级AI助手，请结合轨迹和观察信息给出最终答案。",
                    finalPrompt,
                    routeDecision,
                    tenantId,
                    "react_final",
                    conversationId(request)
            );
            if (StringUtils.hasText(answer)) {
                return new AnswerResult(answer, false);
            }
            log.warn("[react] final answer returned blank content (chatId={}, endpoint=react_final);"
                    + " using canned fallback answer", request.getChatId());
        } catch (RuntimeException ex) {
            log.warn("[react] final answer failed: {}", ex.toString(), ex);
        }
        return new AnswerResult(CANNED_FALLBACK_ANSWER, true);
    }

    private String buildFinalPrompt(ReactChatRequestVO request,
                                    List<ReactTraceStepVO> trace,
                                    String rollingContext) {
        return """
                用户问题:
                %s
                %n
                ReAct轨迹:
                %s
                %n
                观察上下文:
                %s
                %n
                请输出最终中文答案，要求简洁、可执行、结构清晰。
                %n""".formatted(request.getPrompt(), responseFormatter.toJson(trace), capContext(rollingContext));
    }

    /**
     * 把 rolling context 折叠进 prompt 前做长度收敛：空白归空串，超过
     * {@value #MAX_ROLLING_CONTEXT_CHARS} 字符时保留最新的尾部并标注截断，
     * 保证多步循环下 prompt 体积有界。
     */
    private String capContext(String rollingContext) {
        String context = emptyIfBlank(rollingContext);
        if (context.length() <= MAX_ROLLING_CONTEXT_CHARS) {
            return context;
        }
        return "...(earlier observations truncated)...\n"
                + context.substring(context.length() - MAX_ROLLING_CONTEXT_CHARS);
    }

    /**
     * 经 {@link ModelRouter} 解析本次请求的模型档位/成本层级/实验分组；
     * 结果在同一请求内的所有模型调用间复用。
     */
    private ModelRouter.ModelRouteDecision resolveRouteDecision(String requestedProfile,
                                                                String endpoint,
                                                                String subjectKey,
                                                                String tenantId) {
        return modelRouter.resolve(requestedProfile, endpoint, tenantId, subjectKey);
    }

    /**
     * 构建按路由决策选定模型、并绑定 ChatMemory 会话 id 的提示词规格，
     * 供阻塞与流式两种调用复用。
     */
    private ChatClient.ChatClientRequestSpec routedPrompt(ModelRouter.ModelRouteDecision decision,
                                                          String conversationId) {
        return chatClient.prompt()
                .options(ChatOptions.builder().model(decision.model()).build())
                .advisors(a -> a.param(CONVERSATION_ID, conversationId));
    }

    /** ChatMemory advisor 要求会话 id 非空，缺省时退回 default 会话。 */
    private String conversationId(ReactChatRequestVO request) {
        String chatId = StringUtils.hasText(request.getChatId()) ? request.getChatId() : "default";
        return ConversationIdHelper.build("react", chatId);
    }

    /**
     * 阻塞式模型调用，带租户预算管控：调用前估算输入 token 并断言预算，
     * 调用后按实际输出 token 记录用量（{@code endpointTag} 用于成本归因）。
     */
    private String callModel(String systemPrompt,
                             String userPrompt,
                             ModelRouter.ModelRouteDecision routeDecision,
                             String tenantId,
                             String endpointTag,
                             String conversationId) {
        if (routeDecision == null) {
            throw new IllegalStateException("model route decision is missing for endpoint '" + endpointTag
                    + "' (tenantId=" + tenantId + "); cannot resolve model to call");
        }
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);
        String output = routedPrompt(routeDecision, conversationId)
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
        long outputTokens = tenantCostService.estimateTokens(output);
        tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens, outputTokens, endpointTag);
        // 模型返回 null 时归一为空串，调用方按"空白答案"走各自的降级路径。
        return StringUtils.hasText(output) ? output : "";
    }

    /**
     * {@link #callModel} 的流式变体：同样在调用前断言预算，输出用量在
     * token 流终止后的 {@code doFinally} 中恰好记录一次（经 side StringBuilder
     * 汇总，{@code AtomicBoolean} 防重）。
     */
    private Flux<String> callModelStream(String systemPrompt,
                                         String userPrompt,
                                         ModelRouter.ModelRouteDecision routeDecision,
                                         String tenantId,
                                         String endpointTag,
                                         String conversationId) {
        if (routeDecision == null) {
            throw new IllegalStateException("model route decision is missing for endpoint '" + endpointTag
                    + "' (tenantId=" + tenantId + "); cannot resolve model to stream from");
        }
        long inputTokens = tenantCostService.estimateTokens(systemPrompt) + tenantCostService.estimateTokens(userPrompt);
        tenantCostService.assertBudget(tenantId, routeDecision.costTier(), inputTokens, 600);

        StringBuilder outputCollector = new StringBuilder();
        AtomicBoolean usageRecorded = new AtomicBoolean(false);
        return routedPrompt(routeDecision, conversationId)
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(chunk -> outputCollector.append(emptyIfBlank(chunk)))
                .doFinally(signalType -> {
                    if (!usageRecorded.compareAndSet(false, true)) {
                        return;
                    }
                    long outputTokens = tenantCostService.estimateTokens(outputCollector.toString());
                    tenantCostService.recordUsage(tenantId, routeDecision.costTier(), inputTokens, outputTokens, endpointTag);
                });
    }

    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    private String currentTenantId() {
        return TenantContext.normalize(MDC.get(TenantContext.TENANT_REQUEST_ATTRIBUTE));
    }

    /** 请求校验：prompt 与 chatId 均为必填，缺失即抛 {@link IllegalArgumentException}。 */
    private void validateRequest(ReactChatRequestVO request) {
        if (request == null || !StringUtils.hasText(request.getPrompt())) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (!StringUtils.hasText(request.getChatId())) {
            throw new IllegalArgumentException("chatId is required");
        }
    }

    /** 最终答案及其 fallback 标记的内部载体（fallback=true 表示来自降级路径）。 */
    private record AnswerResult(String answer, boolean fallback) {
    }
}
