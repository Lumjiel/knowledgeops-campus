package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalCaseCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalComparisonVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetVO;
import com.enterprise.iqk.evaluation.vo.EvalResultVO;
import com.enterprise.iqk.evaluation.vo.EvalRunRequestVO;
import com.enterprise.iqk.evaluation.vo.EvalRunVO;
import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.util.ConversationIdHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EvaluationService 的纯单元测试。真实协作者仅 {@link ObjectMapper}（与生产
 * 装配一致），四个 mapper、HybridRagAnswerService、EvaluationScorer、
 * EvaluationReportRenderer 全部 mock——本类测试聚焦编排与汇总逻辑，
 * 打分口径由 {@link EvaluationScorerTest} 覆盖。
 *
 * <p>triggerRun 的执行约定（与被测实现对齐）：run 记录先以 RUNNING 落库，
 * 逐 case 调 RAG 后插 eval_result，最后同一实例回填汇总并以 updateById 收尾；
 * 失败 case 不抛异常，而是记 FAILED 后继续跑完整个 run。
 */
class EvaluationServiceTest {

    private static final String TENANT = "t1";
    private static final String DATASET_ID = "ds-1";

    private final EvalDatasetMapper datasetMapper = mock(EvalDatasetMapper.class);
    private final EvalCaseMapper caseMapper = mock(EvalCaseMapper.class);
    private final EvalRunMapper runMapper = mock(EvalRunMapper.class);
    private final EvalResultMapper resultMapper = mock(EvalResultMapper.class);
    private final HybridRagAnswerService ragService = mock(HybridRagAnswerService.class);
    private final EvaluationScorer scorer = mock(EvaluationScorer.class);
    private final EvaluationReportRenderer renderer = mock(EvaluationReportRenderer.class);
    private final EvaluationService service = new EvaluationService(
            datasetMapper, caseMapper, runMapper, resultMapper, ragService,
            new ObjectMapper(), scorer, renderer);

    // ── createDataset()：参数校验与落库归一化 ────────────────────

    @Test
    void createDatasetRejectsNullPayload() {
        assertThatThrownBy(() -> service.createDataset(TENANT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset payload is required");
        verifyNoInteractions(datasetMapper, caseMapper);
    }

    @Test
    void createDatasetRejectsBlankName() {
        assertThatThrownBy(() -> service.createDataset(TENANT, request("   ", List.of(createCase("问题?")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset name is required");
        verifyNoInteractions(datasetMapper, caseMapper);
    }

    @Test
    void createDatasetRejectsMissingCases() {
        assertThatThrownBy(() -> service.createDataset(TENANT, request("数据集", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset cases are required");
        verifyNoInteractions(datasetMapper, caseMapper);
    }

    @Test
    void createDatasetRejectsCaseWithoutQuestion() {
        EvalCaseCreateVO blank = createCase(" ", "   ");

        assertThatThrownBy(() -> service.createDataset(TENANT, request("数据集", List.of(blank))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("case question is required");
        // 数据集行已插入，但不得写入任何 case 行
        verify(datasetMapper).insert(any(EvalDatasetRecord.class));
        verify(caseMapper, never()).insert(any(EvalCaseRecord.class));
    }

    @Test
    void createDatasetGeneratesIdsDefaultsAndSortOrder() {
        EvalCaseCreateVO explicit = createCase("高温处置?");
        explicit.setCaseId(" custom-id ");
        explicit.setCategory(" 灾害 ");
        explicit.setExpectedCitations(List.of("heat-policy"));
        EvalCaseCreateVO generated = createCase("洪水预案?");
        EvalDatasetCreateVO request = request(" 评测集 ", List.of(explicit, generated));
        request.setDescription("   ");

        EvalDatasetVO result = service.createDataset(" " + TENANT + " ", request);

        ArgumentCaptor<EvalDatasetRecord> datasetCaptor = ArgumentCaptor.forClass(EvalDatasetRecord.class);
        verify(datasetMapper).insert(datasetCaptor.capture());
        EvalDatasetRecord dataset = datasetCaptor.getValue();
        assertThat(dataset.getDatasetId()).startsWith("eval-ds-").hasSize("eval-ds-".length() + 12);
        assertThat(dataset.getTenantId()).isEqualTo(TENANT);
        assertThat(dataset.getName()).isEqualTo("评测集");
        assertThat(dataset.getDescription()).isEmpty();

        ArgumentCaptor<EvalCaseRecord> caseCaptor = ArgumentCaptor.forClass(EvalCaseRecord.class);
        verify(caseMapper, times(2)).insert(caseCaptor.capture());
        List<EvalCaseRecord> insertedCases = caseCaptor.getAllValues();
        // 显式 caseId 去空白；未提供的按 001/002 序号生成；空 category/chatId 折叠为 ""
        assertThat(insertedCases.get(0).getCaseId()).isEqualTo("custom-id");
        // category 仅做空值折叠、不做 trim（与 name/question 的处理不同）
        assertThat(insertedCases.get(0).getCategory()).isEqualTo(" 灾害 ");
        assertThat(insertedCases.get(0).getSortOrder()).isZero();
        assertThat(insertedCases.get(0).getExpectedCitationsJson()).isEqualTo("[\"heat-policy\"]");
        assertThat(insertedCases.get(0).getExpectedKeywordsJson()).isEqualTo("[]");
        assertThat(insertedCases.get(0).getForbiddenKeywordsJson()).isEqualTo("[]");
        assertThat(insertedCases.get(1).getCaseId()).isEqualTo("case-002");
        assertThat(insertedCases.get(1).getSortOrder()).isEqualTo(1);
        assertThat(insertedCases.get(1).getDatasetId()).isEqualTo(dataset.getDatasetId());

        assertThat(result.getCaseCount()).isEqualTo(2);
        assertThat(result.getName()).isEqualTo("评测集");
        assertThat(result.getCreatedAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }

    // ── listDatasets()：逐数据集统计 case 数 ─────────────────────

    @Test
    void listDatasetsCountsCasesPerDataset() {
        when(datasetMapper.findByTenant("public")).thenReturn(List.of(dataset("ds-1"), dataset("ds-2")));
        when(caseMapper.findByTenantAndDatasetId("public", "ds-1")).thenReturn(List.of(evalCase("c1", "问?", null)));
        when(caseMapper.findByTenantAndDatasetId("public", "ds-2"))
                .thenReturn(List.of(evalCase("c2", "问?", null), evalCase("c3", "问?", null), evalCase("c4", "问?", null)));

        List<EvalDatasetVO> datasets = service.listDatasets(null);

        assertThat(datasets).hasSize(2);
        assertThat(datasets.get(0).getDatasetId()).isEqualTo("ds-1");
        assertThat(datasets.get(0).getCaseCount()).isEqualTo(1);
        assertThat(datasets.get(1).getCaseCount()).isEqualTo(3);
    }

    // ── triggerRun()：编排、失败兜底与汇总落库 ───────────────────

    @Test
    void triggerRunRejectsBlankOrUnknownDataset() {
        assertThatThrownBy(() -> service.triggerRun(TENANT, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset id is required");

        when(datasetMapper.findByTenantAndDatasetId(TENANT, "ghost")).thenReturn(null);
        assertThatThrownBy(() -> service.triggerRun(TENANT, "ghost", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset not found");
        verify(runMapper, never()).insert(any(EvalRunRecord.class));
    }

    @Test
    void triggerRunRejectsDatasetWithNoCases() {
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.triggerRun(TENANT, DATASET_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset has no cases");
        verify(runMapper, never()).insert(any(EvalRunRecord.class));
        verifyNoInteractions(ragService);
    }

    @Test
    void triggerRunExecutesAllCasesAndPersistsSummary() {
        EvalCaseRecord case1 = evalCase("c1", "高温处置?", "chat-1");
        EvalCaseRecord case2 = evalCase("c2", "洪水预案?", null);
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of(case1, case2));
        // 注意：answer() 收到的是 request.getModelProfile() 原始值（未 trim），
        // trim 只发生在 run 记录的 modelProfile 上
        when(ragService.answer(eq("高温处置?"), eq(TENANT), eq("chat-1"),
                eq(ConversationIdHelper.build("eval", "chat-1")), eq(" fast ")))
                .thenReturn(ragResult("答案 [1]",
                        List.of(CitationItem.builder().sourceType("vector").title("文档").chunkId("chunk-1").build()),
                        List.of(EvidenceItem.builder().snippet("证据").build())));
        when(ragService.answer(eq("洪水预案?"), eq(TENANT), eq("run-002"),
                eq(ConversationIdHelper.build("eval", "run-002")), eq(" fast ")))
                .thenReturn(ragResult("洪水答案", List.of(), List.of()));
        when(scorer.scoreCase(same(case1), eq("答案 [1]"), eq(List.of("vector:文档:chunk-1")),
                eq(List.of("证据")), eq(false)))
                .thenReturn(new EvaluationScorer.CaseScores(1.0, 1.0, 1.0, 1.0, 0.9));
        when(scorer.scoreCase(same(case2), eq("洪水答案"), eq(List.of()), eq(List.of()), eq(false)))
                .thenReturn(new EvaluationScorer.CaseScores(0.5, 0.5, 0.5, 0.5, 0.5));

        EvalRunRequestVO request = new EvalRunRequestVO();
        request.setModelProfile(" fast ");
        request.setChatIdPrefix("run");

        EvalRunVO result = service.triggerRun(TENANT, DATASET_ID, request);

        // 每条 case 一次 RAG 调用；无 chatId 的 case 用 chatIdPrefix + 序号
        verify(ragService, times(2)).answer(anyString(), anyString(), anyString(), anyString(), anyString());

        ArgumentCaptor<EvalRunRecord> insertCaptor = ArgumentCaptor.forClass(EvalRunRecord.class);
        verify(runMapper).insert(insertCaptor.capture());
        EvalRunRecord started = insertCaptor.getValue();
        assertThat(started.getRunId()).startsWith("eval-run-");
        assertThat(started.getStatus()).isEqualTo("RUNNING");
        assertThat(started.getModelProfile()).isEqualTo("fast");
        assertThat(started.getTotalCases()).isEqualTo(2);

        ArgumentCaptor<EvalResultRecord> resultCaptor = ArgumentCaptor.forClass(EvalResultRecord.class);
        verify(resultMapper, times(2)).insert(resultCaptor.capture());
        List<EvalResultRecord> stored = resultCaptor.getAllValues();
        assertThat(stored.get(0).getCaseId()).isEqualTo("c1");
        assertThat(stored.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(stored.get(0).getResultId()).startsWith("eval-result-");
        assertThat(stored.get(0).getCitationsJson()).contains("vector:文档:chunk-1");
        assertThat(stored.get(0).getEvidenceJson()).contains("证据");
        assertThat(stored.get(0).getScore()).isEqualTo(0.9);
        // 无 chatId 的 case 走 chatIdPrefix-002 路径（由上方 answer 桩命中验证）
        assertThat(stored.get(1).getCaseId()).isEqualTo("c2");

        // 同一 run 实例回填汇总后 updateById 收尾
        ArgumentCaptor<EvalRunRecord> updateCaptor = ArgumentCaptor.forClass(EvalRunRecord.class);
        verify(runMapper).updateById(updateCaptor.capture());
        EvalRunRecord finished = updateCaptor.getValue();
        assertThat(finished).isSameAs(started);
        assertThat(finished.getStatus()).isEqualTo("SUCCESS");
        // 仅 score >= 0.70 的 SUCCESS case 计入通过数
        assertThat(finished.getPassedCases()).isEqualTo(1);
        assertThat(finished.getRunScore()).isEqualTo(0.7);
        assertThat(finished.getRetrievalHitRate()).isEqualTo(0.75);
        assertThat(finished.getFailureRate()).isZero();
        assertThat(finished.getFinishedAt()).isNotNull();

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getMetrics().getTotalCases()).isEqualTo(2);
        assertThat(result.getResults()).hasSize(2);
        assertThat(result.getResults().get(0).getCitations()).containsExactly("vector:文档:chunk-1");
        assertThat(result.getResults().get(0).getEvidence()).containsExactly("证据");
    }

    @Test
    void triggerRunMarksEmptyAnswerAsFailedAndExcludesFromPassed() {
        EvalCaseRecord case1 = evalCase("c1", "问题一?", "chat-1");
        EvalCaseRecord case2 = evalCase("c2", "问题二?", "chat-2");
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of(case1, case2));
        when(ragService.answer(eq("问题一?"), eq(TENANT), eq("chat-1"), anyString(), eq("fast")))
                .thenReturn(ragResult("   ", List.of(), List.of()));
        when(ragService.answer(eq("问题二?"), eq(TENANT), eq("chat-2"), anyString(), eq("fast")))
                .thenReturn(ragResult("正常回答", List.of(), List.of()));
        // 失败 case 打分仍会执行（failed=true），即便给了高分也不计入通过
        when(scorer.scoreCase(same(case1), eq(""), eq(List.of()), eq(List.of()), eq(true)))
                .thenReturn(new EvaluationScorer.CaseScores(0.0, 0.0, 0.0, 0.0, 0.8));
        when(scorer.scoreCase(same(case2), eq("正常回答"), eq(List.of()), eq(List.of()), eq(false)))
                .thenReturn(new EvaluationScorer.CaseScores(1.0, 1.0, 1.0, 1.0, 0.8));

        EvalRunRequestVO request = new EvalRunRequestVO();
        request.setModelProfile("fast");

        EvalRunVO result = service.triggerRun(TENANT, DATASET_ID, request);

        verify(scorer).scoreCase(same(case1), eq(""), eq(List.of()), eq(List.of()), eq(true));
        ArgumentCaptor<EvalResultRecord> resultCaptor = ArgumentCaptor.forClass(EvalResultRecord.class);
        verify(resultMapper, times(2)).insert(resultCaptor.capture());
        assertThat(resultCaptor.getAllValues().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(resultCaptor.getAllValues().get(0).getErrorMessage()).isEqualTo("empty answer");

        assertThat(result.getMetrics().getPassedCases()).isEqualTo(1);
        assertThat(result.getMetrics().getFailureRate()).isEqualTo(0.5);
    }

    @Test
    void triggerRunCapturesFailureMessagesFromExceptions() {
        EvalCaseRecord case1 = evalCase("c1", "问题一?", "chat-1");
        EvalCaseRecord case2 = evalCase("c2", "问题二?", "chat-2");
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of(case1, case2));
        when(ragService.answer(eq("问题一?"), eq(TENANT), eq("chat-1"), anyString(), eq("fast")))
                .thenThrow(new RuntimeException("检索服务不可用"));
        // 无消息的异常回落固定文案，且失败不中断整个 run
        when(ragService.answer(eq("问题二?"), eq(TENANT), eq("chat-2"), anyString(), eq("fast")))
                .thenThrow(new IllegalStateException());
        when(scorer.scoreCase(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new EvaluationScorer.CaseScores(0.0, 0.0, 0.0, 0.0, 0.0));

        EvalRunRequestVO request = new EvalRunRequestVO();
        request.setModelProfile("fast");

        EvalRunVO result = service.triggerRun(TENANT, DATASET_ID, request);

        ArgumentCaptor<EvalResultRecord> resultCaptor = ArgumentCaptor.forClass(EvalResultRecord.class);
        verify(resultMapper, times(2)).insert(resultCaptor.capture());
        List<EvalResultRecord> stored = resultCaptor.getAllValues();
        assertThat(stored.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(stored.get(0).getErrorMessage()).isEqualTo("检索服务不可用");
        assertThat(stored.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(stored.get(1).getErrorMessage()).isEqualTo("evaluation case failed");

        assertThat(result.getMetrics().getPassedCases()).isZero();
        assertThat(result.getMetrics().getFailureRate()).isEqualTo(1.0);
        assertThat(result.getMetrics().getRunScore()).isZero();
    }

    @Test
    void triggerRunDefaultsModelProfileAndChatIdWhenRequestIsNull() {
        EvalCaseRecord case1 = evalCase("c1", "问题?", null);
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of(case1));
        // request 为 null 时 chatId 回落数据集 id、modelProfile 传 null
        when(ragService.answer(eq("问题?"), eq(TENANT), eq(DATASET_ID),
                eq(ConversationIdHelper.build("eval", DATASET_ID)), isNull()))
                .thenReturn(ragResult("ok", List.of(), List.of()));
        when(scorer.scoreCase(same(case1), eq("ok"), eq(List.of()), eq(List.of()), eq(false)))
                .thenReturn(new EvaluationScorer.CaseScores(1.0, 1.0, 1.0, 1.0, 0.9));

        EvalRunVO result = service.triggerRun(TENANT, DATASET_ID, null);

        verify(ragService, times(1)).answer(anyString(), anyString(), anyString(), anyString(), isNull());
        ArgumentCaptor<EvalRunRecord> updateCaptor = ArgumentCaptor.forClass(EvalRunRecord.class);
        verify(runMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getModelProfile()).isEqualTo("balanced");
        assertThat(result.getMetrics().getPassedCases()).isEqualTo(1);
    }

    // ── getRun()：读回映射与零值兜底 ─────────────────────────────

    @Test
    void getRunRejectsBlankOrUnknownRunId() {
        assertThatThrownBy(() -> service.getRun(TENANT, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("run id is required");

        when(runMapper.findByTenantAndRunId(TENANT, "ghost")).thenReturn(null);
        assertThatThrownBy(() -> service.getRun(TENANT, "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("run not found");
    }

    @Test
    void getRunMapsMetricsAndResultsWithZeroDefaults() {
        when(runMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(runRecord("run-1"));
        when(resultMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(List.of(
                EvalResultRecord.builder()
                        .resultId("eval-result-1").caseId("c1").status("SUCCESS")
                        .questionText("问题?").answerText("答案")
                        .citationsJson("[\"doc-1\",\"doc-2\"]").evidenceJson("[\"片段\"]")
                        .retrievalHit(1.0).keywordScore(null).score(0.85).latencyMs(120L)
                        .build(),
                EvalResultRecord.builder()
                        .resultId("eval-result-2").caseId("c2").status("FAILED")
                        .errorMessage("超时")
                        .citationsJson(null).score(null).latencyMs(null)
                        .build()));

        EvalRunVO result = service.getRun(TENANT, "run-1");

        assertThat(result.getRunId()).isEqualTo("run-1");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getModelProfile()).isEqualTo("deep");
        // null 指标统一兜 0，时间戳缺失给空串
        assertThat(result.getMetrics().getTotalCases()).isEqualTo(10);
        assertThat(result.getMetrics().getPassedCases()).isEqualTo(7);
        assertThat(result.getMetrics().getRunScore()).isEqualTo(0.7);
        assertThat(result.getMetrics().getRetrievalHitRate()).isZero();
        assertThat(result.getFinishedAt()).isEmpty();

        List<EvalResultVO> results = result.getResults();
        assertThat(results.get(0).getCitations()).containsExactly("doc-1", "doc-2");
        assertThat(results.get(0).getEvidence()).containsExactly("片段");
        assertThat(results.get(0).getKeywordScore()).isZero();
        assertThat(results.get(0).getScore()).isEqualTo(0.85);
        assertThat(results.get(0).getLatencyMs()).isEqualTo(120L);
        assertThat(results.get(1).getCitations()).isEmpty();
        assertThat(results.get(1).getScore()).isZero();
        assertThat(results.get(1).getLatencyMs()).isZero();
        assertThat(results.get(1).getErrorMessage()).isEqualTo("超时");
    }

    // ── markBaseline()：基线落库 ─────────────────────────────────

    @Test
    void markBaselineThrowsWhenDatasetMissing() {
        when(runMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(runRecord("run-1"));
        when(datasetMapper.updateBaselineRunId(TENANT, DATASET_ID, "run-1")).thenReturn(0);

        assertThatThrownBy(() -> service.markBaseline(TENANT, "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dataset not found");
    }

    @Test
    void markBaselineUpdatesBaselineAndReturnsRun() {
        when(runMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(runRecord("run-1"));
        when(datasetMapper.updateBaselineRunId(TENANT, DATASET_ID, "run-1")).thenReturn(1);
        when(resultMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(List.of());

        EvalRunVO result = service.markBaseline(TENANT, "run-1");

        verify(datasetMapper).updateBaselineRunId(TENANT, DATASET_ID, "run-1");
        assertThat(result.getRunId()).isEqualTo("run-1");
    }

    // ── compareLatest()：基线选择与历史兜底 ──────────────────────

    @Test
    void compareLatestFallsBackToPreviousRunWhenNoBaseline() {
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of(evalCase("c1", "问?", null)));
        when(runMapper.findRecentByDatasetId(TENANT, DATASET_ID, 2)).thenReturn(List.of(runRecord("run-2"), runRecord("run-1")));
        when(resultMapper.findByTenantAndRunId(TENANT, "run-2")).thenReturn(List.of());
        when(resultMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(List.of());

        EvalComparisonVO comparison = service.compareLatest(TENANT, DATASET_ID);

        assertThat(comparison.getCurrent().getRunId()).isEqualTo("run-2");
        // 未配置基线时回落到次新 run
        assertThat(comparison.getBaseline().getRunId()).isEqualTo("run-1");
        assertThat(comparison.getDataset().getDatasetId()).isEqualTo(DATASET_ID);
        assertThat(comparison.getDataset().getCaseCount()).isEqualTo(1);
    }

    @Test
    void compareLatestPrefersExplicitBaselineRun() {
        EvalDatasetRecord dataset = dataset();
        dataset.setBaselineRunId("run-base");
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset);
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of());
        when(runMapper.findRecentByDatasetId(TENANT, DATASET_ID, 2)).thenReturn(List.of(runRecord("run-2")));
        when(runMapper.findByTenantAndRunId(TENANT, "run-base")).thenReturn(runRecord("run-base"));
        when(resultMapper.findByTenantAndRunId(TENANT, "run-base")).thenReturn(List.of());
        when(resultMapper.findByTenantAndRunId(TENANT, "run-2")).thenReturn(List.of());

        EvalComparisonVO comparison = service.compareLatest(TENANT, DATASET_ID);

        assertThat(comparison.getCurrent().getRunId()).isEqualTo("run-2");
        assertThat(comparison.getBaseline().getRunId()).isEqualTo("run-base");
    }

    @Test
    void compareLatestReturnsNullRunsWhenNoHistory() {
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset());
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of());
        when(runMapper.findRecentByDatasetId(TENANT, DATASET_ID, 2)).thenReturn(List.of());

        EvalComparisonVO comparison = service.compareLatest(TENANT, DATASET_ID);

        assertThat(comparison.getCurrent()).isNull();
        assertThat(comparison.getBaseline()).isNull();
        assertThat(comparison.getDataset().getDatasetId()).isEqualTo(DATASET_ID);
    }

    @Test
    void compareLatestFallsBackWhenConfiguredBaselineRunMissing() {
        EvalDatasetRecord dataset = dataset();
        dataset.setBaselineRunId("run-ghost");
        when(datasetMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(dataset);
        when(caseMapper.findByTenantAndDatasetId(TENANT, DATASET_ID)).thenReturn(List.of());
        when(runMapper.findRecentByDatasetId(TENANT, DATASET_ID, 2)).thenReturn(List.of(runRecord("run-2"), runRecord("run-1")));
        when(runMapper.findByTenantAndRunId(TENANT, "run-ghost")).thenReturn(null);
        when(resultMapper.findByTenantAndRunId(TENANT, "run-2")).thenReturn(List.of());
        when(resultMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(List.of());

        EvalComparisonVO comparison = service.compareLatest(TENANT, DATASET_ID);

        // 配置的基线 run 已不存在 → 仍回落次新 run，不致 NPE
        assertThat(comparison.getCurrent().getRunId()).isEqualTo("run-2");
        assertThat(comparison.getBaseline().getRunId()).isEqualTo("run-1");
    }

    // ── exportReport()：委托渲染器 ──────────────────────────────

    @Test
    void exportReportDelegatesToRenderer() {
        when(runMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(runRecord("run-1"));
        when(resultMapper.findByTenantAndRunId(TENANT, "run-1")).thenReturn(List.of());
        when(renderer.render(any(EvalRunVO.class))).thenReturn("# 评测报告");

        String report = service.exportReport(TENANT, "run-1");

        assertThat(report).isEqualTo("# 评测报告");
        ArgumentCaptor<EvalRunVO> voCaptor = ArgumentCaptor.forClass(EvalRunVO.class);
        verify(renderer).render(voCaptor.capture());
        assertThat(voCaptor.getValue().getRunId()).isEqualTo("run-1");
    }

    // ── 测试脚手架 ──────────────────────────────────────────────

    private EvalDatasetCreateVO request(String name, List<EvalCaseCreateVO> cases) {
        EvalDatasetCreateVO request = new EvalDatasetCreateVO();
        request.setName(name);
        request.setCases(cases);
        return request;
    }

    private EvalCaseCreateVO createCase(String question) {
        EvalCaseCreateVO item = new EvalCaseCreateVO();
        item.setQuestion(question);
        return item;
    }

    private EvalDatasetRecord dataset() {
        return dataset(DATASET_ID);
    }

    private EvalDatasetRecord dataset(String datasetId) {
        return EvalDatasetRecord.builder()
                .datasetId(datasetId)
                .tenantId(TENANT)
                .name("数据集")
                .description("")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
    }

    private EvalCaseRecord evalCase(String caseId, String question, String chatId) {
        return EvalCaseRecord.builder()
                .caseId(caseId)
                .datasetId(DATASET_ID)
                .tenantId(TENANT)
                .questionText(question)
                .chatId(chatId)
                .expectedKeywordsJson("[]")
                .expectedCitationsJson("[]")
                .forbiddenKeywordsJson("[]")
                .build();
    }

    private EvalRunRecord runRecord(String runId) {
        return EvalRunRecord.builder()
                .runId(runId)
                .datasetId(DATASET_ID)
                .tenantId(TENANT)
                .status("SUCCESS")
                .modelProfile("deep")
                .totalCases(10)
                .passedCases(7)
                .runScore(0.7)
                .citationCoverageRate(0.8)
                .failureRate(0.3)
                .startedAt(LocalDateTime.of(2026, 1, 1, 8, 0))
                .createdAt(LocalDateTime.of(2026, 1, 1, 8, 0))
                .build();
    }

    private HybridRagAnswerService.HybridRagResult ragResult(String answer,
                                                             List<CitationItem> citations,
                                                             List<EvidenceItem> evidence) {
        return HybridRagAnswerService.HybridRagResult.builder()
                .answer(answer)
                .citations(citations)
                .evidence(evidence)
                .build();
    }
}
