package com.enterprise.iqk.evaluation;

import com.enterprise.iqk.evaluation.vo.EvalCaseCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalComparisonVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetCreateVO;
import com.enterprise.iqk.evaluation.vo.EvalDatasetVO;
import com.enterprise.iqk.evaluation.vo.EvalMetricSummaryVO;
import com.enterprise.iqk.evaluation.vo.EvalResultVO;
import com.enterprise.iqk.evaluation.vo.EvalRunRequestVO;
import com.enterprise.iqk.evaluation.vo.EvalRunVO;
import com.enterprise.iqk.rag.HybridRagAnswerService;
import com.enterprise.iqk.retrieval.CitationItem;
import com.enterprise.iqk.retrieval.EvidenceItem;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.util.ConversationIdHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * File: EvaluationService.java
 * <p>
 * Tenant-scoped evaluation engine for the RAG pipeline. Manages datasets and
 * their cases ({@link #createDataset}, {@link #listDatasets}), executes a run
 * by answering every case through {@link HybridRagAnswerService} and scoring
 * it via {@link EvaluationScorer} ({@link #triggerRun}), and provides
 * run inspection ({@link #getRun}), baseline marking ({@link #markBaseline}),
 * baseline-vs-current comparison ({@link #compareLatest}) and report export
 * ({@link #exportReport}).
 * <p>
 * A case fails when the answer is empty, the RAG call throws or it exceeds
 * {@link #PER_CASE_TIMEOUT_SECONDS}; per-case scores are persisted immediately
 * so a crashed run still leaves its partial results behind, and a run aborted
 * mid-way is flipped to FAILED with the cause in {@code errorMessage}. A case
 * passes when its composite score reaches {@link #PASS_THRESHOLD} (0.70).
 * Run-level metrics are averages across cases, rounded to 4 decimals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {
    /** Composite score a case needs (on a 0..1 scale) to count as passed in {@link #summarize}. */
    private static final double PASS_THRESHOLD = 0.70;
    /** Wall-clock budget for one case's RAG call; exceeding it fails the case instead of hanging the run. */
    private static final long PER_CASE_TIMEOUT_SECONDS = 120;
    /** Wall-clock budget for a whole run; once exceeded, remaining cases are skipped as FAILED. */
    private static final long RUN_DEADLINE_SECONDS = 600;

    private final EvalDatasetMapper evalDatasetMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;
    private final HybridRagAnswerService hybridRagAnswerService;
    private final ObjectMapper objectMapper;
    private final EvaluationScorer evaluationScorer;
    private final EvaluationReportRenderer evaluationReportRenderer;

    /**
     * Creates a dataset plus all of its cases in one call. Case ids default to
     * {@code case-001}-style sequence numbers when not supplied; expected
     * citations / keywords / forbidden keywords are serialized to JSON columns.
     * All cases are validated up front (including duplicate explicit case ids)
     * so an invalid payload never leaves a half-created dataset behind.
     * Throws {@link IllegalArgumentException} (message names the offending
     * {@code cases[<index>]}) when the payload, name, any case question is
     * missing or a case id is duplicated.
     */
    public EvalDatasetVO createDataset(String tenantId, EvalDatasetCreateVO request) {
        if (request == null) {
            throw new IllegalArgumentException("dataset payload is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("dataset name is required");
        }
        if (request.getCases() == null || request.getCases().isEmpty()) {
            throw new IllegalArgumentException("dataset cases are required");
        }

        validateCases(request.getCases());

        String tenant = TenantContext.normalize(tenantId);
        String datasetId = "eval-ds-" + shortUuid();
        LocalDateTime now = LocalDateTime.now();
        EvalDatasetRecord dataset = EvalDatasetRecord.builder()
                .datasetId(datasetId)
                .tenantId(tenant)
                .name(request.getName().trim())
                .description(emptyIfBlank(request.getDescription()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        evalDatasetMapper.insert(dataset);

        int order = 0;
        for (EvalCaseCreateVO item : request.getCases()) {
            String caseId = StringUtils.hasText(item.getCaseId())
                    ? item.getCaseId().trim()
                    : "case-" + String.format(Locale.ROOT, "%03d", order + 1);
            evalCaseMapper.insert(EvalCaseRecord.builder()
                    .caseId(caseId)
                    .datasetId(datasetId)
                    .tenantId(tenant)
                    .category(emptyIfBlank(item.getCategory()))
                    .chatId(emptyIfBlank(item.getChatId()))
                    .questionText(item.getQuestion().trim())
                    .expectedCitationsJson(writeJsonList(item.getExpectedCitations()))
                    .expectedKeywordsJson(writeJsonList(item.getExpectedKeywords()))
                    .forbiddenKeywordsJson(writeJsonList(item.getForbiddenKeywords()))
                    .sortOrder(order++)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return toDatasetVO(dataset, order);
    }

    /**
     * Validates every case before any insert happens: each item must carry a
     * question, and explicitly supplied case ids must be unique. Error
     * messages name the offending index so callers can fix the payload.
     */
    private void validateCases(List<EvalCaseCreateVO> cases) {
        Set<String> seenCaseIds = new LinkedHashSet<>();
        for (int order = 0; order < cases.size(); order++) {
            EvalCaseCreateVO item = cases.get(order);
            if (item == null || !StringUtils.hasText(item.getQuestion())) {
                throw new IllegalArgumentException("cases[" + order + "].question is required");
            }
            if (StringUtils.hasText(item.getCaseId()) && !seenCaseIds.add(item.getCaseId().trim())) {
                throw new IllegalArgumentException(
                        "duplicate case id '" + item.getCaseId().trim() + "' at cases[" + order + "]");
            }
        }
    }

    /** Lists all datasets of the tenant, each annotated with its current case count. */
    public List<EvalDatasetVO> listDatasets(String tenantId) {
        String tenant = TenantContext.normalize(tenantId);
        return evalDatasetMapper.findByTenant(tenant).stream()
                .map(record -> toDatasetVO(record, evalCaseMapper.findByTenantAndDatasetId(tenant, record.getDatasetId()).size()))
                .toList();
    }

    /**
     * Executes a full evaluation run: inserts a RUNNING run row, answers and
     * scores every case synchronously via {@link #runCase}, then aggregates
     * the metrics, marks the run SUCCESS and persists it. Note this is a
     * blocking, same-request execution — not an async job. Model profile
     * defaults to "balanced" when not supplied.
     * <p>
     * Guard rails: each case's RAG call gets at most
     * {@link #PER_CASE_TIMEOUT_SECONDS} and the whole run gets at most
     * {@link #RUN_DEADLINE_SECONDS} — overdue cases are recorded as FAILED
     * ("timeout" / "skipped") instead of hanging the request. If an
     * unexpected error aborts the run mid-way, the run row is flipped to
     * FAILED with the root cause in {@code errorMessage} (already-inserted
     * per-case results are kept) and the error is rethrown.
     */
    public EvalRunVO triggerRun(String tenantId, String datasetId, EvalRunRequestVO request) {
        String tenant = TenantContext.normalize(tenantId);
        EvalDatasetRecord dataset = requireDataset(tenant, datasetId);
        List<EvalCaseRecord> cases = evalCaseMapper.findByTenantAndDatasetId(tenant, datasetId);
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("dataset has no cases");
        }

        String runId = "eval-run-" + shortUuid();
        LocalDateTime now = LocalDateTime.now();
        String modelProfile = request == null || !StringUtils.hasText(request.getModelProfile())
                ? "balanced"
                : request.getModelProfile().trim();
        EvalRunRecord run = EvalRunRecord.builder()
                .runId(runId)
                .datasetId(dataset.getDatasetId())
                .tenantId(tenant)
                .status("RUNNING")
                .modelProfile(modelProfile)
                .totalCases(cases.size())
                .passedCases(0)
                .runScore(0.0)
                .retrievalHitRate(0.0)
                .citationCoverageRate(0.0)
                .answerFaithfulnessScore(0.0)
                .avgLatencyMs(0.0)
                .failureRate(0.0)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        evalRunMapper.insert(run);

        ExecutorService ragExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "eval-rag-call");
            thread.setDaemon(true);
            return thread;
        });
        long runDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(RUN_DEADLINE_SECONDS);
        try {
            List<EvalResultRecord> results = new ArrayList<>(cases.size());
            for (int i = 0; i < cases.size(); i++) {
                EvalCaseRecord evalCase = cases.get(i);
                if (evalCase == null || !StringUtils.hasText(evalCase.getQuestionText())) {
                    results.add(skippedCaseRecord(tenant, runId, dataset.getDatasetId(), evalCase, i,
                            "case at index " + i + " is null or has a blank question"));
                    continue;
                }
                if (System.nanoTime() >= runDeadlineNs) {
                    results.add(skippedCaseRecord(tenant, runId, dataset.getDatasetId(), evalCase, i,
                            "run time budget of " + RUN_DEADLINE_SECONDS + "s exceeded; case skipped without RAG call"));
                    continue;
                }
                results.add(runCase(tenant, runId, dataset.getDatasetId(), evalCase, request, i, ragExecutor));
            }

            EvalMetricSummaryVO summary = summarize(results);
            run.setStatus("SUCCESS");
            run.setPassedCases(summary.getPassedCases());
            run.setRunScore(summary.getRunScore());
            run.setRetrievalHitRate(summary.getRetrievalHitRate());
            run.setCitationCoverageRate(summary.getCitationCoverageRate());
            run.setAnswerFaithfulnessScore(summary.getAnswerFaithfulnessScore());
            run.setAvgLatencyMs(summary.getAvgLatencyMs());
            run.setFailureRate(summary.getFailureRate());
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdatedAt(run.getFinishedAt());
            evalRunMapper.updateById(run);

            return toRunVO(run, results);
        } catch (Exception ex) {
            // The run must never be left stuck in RUNNING: record why it died
            // and keep whatever per-case results were already persisted.
            log.warn("evaluation run {} aborted: {}", runId, describeCause(ex), ex);
            run.setStatus("FAILED");
            run.setErrorMessage("evaluation run aborted: " + describeCause(ex));
            run.setFinishedAt(LocalDateTime.now());
            run.setUpdatedAt(run.getFinishedAt());
            evalRunMapper.updateById(run);
            if (ex instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            throw new IllegalStateException("evaluation run aborted: " + describeCause(ex), ex);
        } finally {
            ragExecutor.shutdown();
        }
    }

    /** Returns one run with its per-case results; throws if the run does not exist. */
    public EvalRunVO getRun(String tenantId, String runId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalRunRecord run = requireRun(tenant, runId);
        return toRunVO(run, evalResultMapper.findByTenantAndRunId(tenant, runId));
    }

    /**
     * Marks the given run as the dataset's baseline for {@link #compareLatest};
     * throws if the run or its dataset is gone.
     */
    public EvalRunVO markBaseline(String tenantId, String runId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalRunRecord run = requireRun(tenant, runId);
        int updated = evalDatasetMapper.updateBaselineRunId(tenant, run.getDatasetId(), runId);
        if (updated <= 0) {
            throw new IllegalArgumentException("dataset not found");
        }
        return getRun(tenant, runId);
    }

    /**
     * Builds a baseline-vs-current comparison: baseline is the dataset's
     * marked baseline run when available, otherwise the second-most-recent
     * run; current is the most recent run. Both sides may be null when no
     * runs exist yet.
     */
    public EvalComparisonVO compareLatest(String tenantId, String datasetId) {
        String tenant = TenantContext.normalize(tenantId);
        EvalDatasetRecord dataset = requireDataset(tenant, datasetId);
        List<EvalRunRecord> recent = evalRunMapper.findRecentByDatasetId(tenant, datasetId, 2);
        EvalRunVO current = recent.isEmpty()
                ? null
                : toRunVO(recent.get(0), evalResultMapper.findByTenantAndRunId(tenant, recent.get(0).getRunId()));

        EvalRunVO baseline = null;
        if (StringUtils.hasText(dataset.getBaselineRunId())) {
            EvalRunRecord baselineRun = evalRunMapper.findByTenantAndRunId(tenant, dataset.getBaselineRunId());
            if (baselineRun != null) {
                baseline = toRunVO(baselineRun, evalResultMapper.findByTenantAndRunId(tenant, baselineRun.getRunId()));
            }
        }
        if (baseline == null && recent.size() > 1) {
            EvalRunRecord previous = recent.get(1);
            baseline = toRunVO(previous, evalResultMapper.findByTenantAndRunId(tenant, previous.getRunId()));
        }

        return EvalComparisonVO.builder()
                .dataset(toDatasetVO(dataset, evalCaseMapper.findByTenantAndDatasetId(tenant, datasetId).size()))
                .baseline(baseline)
                .current(current)
                .build();
    }

    /** Renders the run (via {@link #getRun}) as a report document using {@link EvaluationReportRenderer}. */
    public String exportReport(String tenantId, String runId) {
        return evaluationReportRenderer.render(getRun(tenantId, runId));
    }

    /**
     * Answers and scores a single case: calls the RAG pipeline with a
     * {@link #PER_CASE_TIMEOUT_SECONDS} budget (timeout, any failure or an
     * empty answer marks the case FAILED instead of aborting the run),
     * measures latency, applies {@link EvaluationScorer} and persists the
     * result row. The persisted {@code errorMessage} always names the
     * concrete cause (exception class + message); the case id and index are
     * included in the warn log and in the timeout wrapper's message.
     */
    private EvalResultRecord runCase(String tenant,
                                     String runId,
                                     String datasetId,
                                     EvalCaseRecord evalCase,
                                     EvalRunRequestVO request,
                                     int index,
                                     ExecutorService ragExecutor) {
        long startedNs = System.nanoTime();
        String status = "SUCCESS";
        String answer = "";
        String errorMessage = "";
        List<String> citations = List.of();
        List<String> evidence = List.of();

        try {
            HybridRagAnswerService.HybridRagResult result =
                    answerWithTimeout(tenant, evalCase, request, index, ragExecutor);
            if (result == null) {
                status = "FAILED";
                errorMessage = "rag pipeline returned no result";
            } else {
                answer = emptyIfBlank(result.getAnswer());
                citations = toCitationStrings(result.getCitations());
                evidence = toEvidenceStrings(result.getEvidence());
                if (!StringUtils.hasText(answer)) {
                    status = "FAILED";
                    errorMessage = "rag pipeline returned an empty answer";
                }
            }
        } catch (Exception ex) {
            status = "FAILED";
            log.warn("evaluation case {} (index {}) failed: {}", evalCase.getCaseId(), index, describeCause(ex), ex);
            errorMessage = describeCause(ex);
        }

        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        EvaluationScorer.CaseScores scores = evaluationScorer.scoreCase(
                evalCase,
                answer,
                citations,
                evidence,
                "FAILED".equals(status)
        );
        EvalResultRecord record = EvalResultRecord.builder()
                .resultId("eval-result-" + shortUuid())
                .runId(runId)
                .datasetId(datasetId)
                .caseId(evalCase.getCaseId())
                .tenantId(tenant)
                .status(status)
                .questionText(evalCase.getQuestionText())
                .answerText(answer)
                .citationsJson(writeJsonList(citations))
                .evidenceJson(writeJsonList(evidence))
                .retrievalHit(scores.retrievalHit())
                .citationCoverage(scores.citationCoverage())
                .keywordScore(scores.keywordScore())
                .answerFaithfulness(scores.answerFaithfulness())
                .score(scores.score())
                .latencyMs(latencyMs)
                .errorMessage(errorMessage)
                .createdAt(LocalDateTime.now())
                .build();
        evalResultMapper.insert(record);
        return record;
    }

    /**
     * Runs one RAG answer call on the run-scoped executor with a hard
     * {@link #PER_CASE_TIMEOUT_SECONDS} budget. Timeout / interruption /
     * worker failure are rethrown as exceptions whose message names the case,
     * so {@link #runCase} can persist a precise failure reason. Note a timed
     * out worker thread cannot be forcibly killed; it is cancelled and left
     * to finish (or die) in the background while the run moves on.
     */
    private HybridRagAnswerService.HybridRagResult answerWithTimeout(String tenant,
                                                                     EvalCaseRecord evalCase,
                                                                     EvalRunRequestVO request,
                                                                     int index,
                                                                     ExecutorService ragExecutor) {
        String chatId = resolveChatId(evalCase, request, index);
        String question = evalCase.getQuestionText();
        Future<HybridRagAnswerService.HybridRagResult> future = ragExecutor.submit(
                () -> hybridRagAnswerService.answer(
                        question,
                        tenant,
                        chatId,
                        ConversationIdHelper.build("eval", chatId),
                        request == null ? null : request.getModelProfile()
                ));
        try {
            return future.get(PER_CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("rag call timed out after " + PER_CASE_TIMEOUT_SECONDS
                    + "s (caseId=" + evalCase.getCaseId() + ", index=" + index + ")", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            throw new IllegalStateException("rag call failed (caseId=" + evalCase.getCaseId()
                    + ", index=" + index + "): " + describeThrowable(cause), cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rag call interrupted (caseId=" + evalCase.getCaseId()
                    + ", index=" + index + ")", ex);
        }
    }

    /**
     * Builds the FAILED result row for a case that never reached the RAG
     * pipeline (blank question, or the run already exceeded its time budget),
     * so the persisted results stay aligned with the dataset's case list.
     */
    private EvalResultRecord skippedCaseRecord(String tenant,
                                               String runId,
                                               String datasetId,
                                               EvalCaseRecord evalCase,
                                               int index,
                                               String reason) {
        String caseId = evalCase == null ? null : evalCase.getCaseId();
        log.warn("evaluation case {} (index {}) skipped: {}", caseId, index, reason);
        EvalResultRecord.EvalResultRecordBuilder builder = EvalResultRecord.builder()
                .resultId("eval-result-" + shortUuid())
                .runId(runId)
                .datasetId(datasetId)
                .caseId(caseId)
                .tenantId(tenant)
                .status("FAILED")
                .answerText("")
                .citationsJson("[]")
                .evidenceJson("[]")
                .latencyMs(0L)
                .errorMessage(reason)
                .createdAt(LocalDateTime.now());
        if (evalCase != null) {
            builder.questionText(evalCase.getQuestionText());
        }
        EvalResultRecord record = builder.build();
        evalResultMapper.insert(record);
        return record;
    }

    /** Human-readable cause: exception class + message, never blank. */
    private String describeCause(Throwable ex) {
        return describeThrowable(ex == null ? new IllegalStateException("unknown error") : ex);
    }

    /** See {@link #describeCause}. */
    private String describeThrowable(Throwable ex) {
        String message = ex.getMessage();
        return ex.getClass().getSimpleName() + (StringUtils.hasText(message) ? ": " + message : "");
    }

    /**
     * Aggregates per-case records into run-level metrics: pass count at
     * {@link #PASS_THRESHOLD}, null-safe averages of the four scores and
     * latency, and the non-SUCCESS failure rate — all rounded to 4 decimals.
     */
    private EvalMetricSummaryVO summarize(List<EvalResultRecord> results) {
        int total = results.size();
        int passed = (int) results.stream()
                .filter(item -> "SUCCESS".equals(item.getStatus()))
                .filter(item -> item.getScore() != null && item.getScore() >= PASS_THRESHOLD)
                .count();
        double totalSafe = Math.max(1, total);
        return EvalMetricSummaryVO.builder()
                .totalCases(total)
                .passedCases(passed)
                .runScore(round(avg(results.stream().map(EvalResultRecord::getScore).toList())))
                .retrievalHitRate(round(avg(results.stream().map(EvalResultRecord::getRetrievalHit).toList())))
                .citationCoverageRate(round(avg(results.stream().map(EvalResultRecord::getCitationCoverage).toList())))
                .answerFaithfulnessScore(round(avg(results.stream().map(EvalResultRecord::getAnswerFaithfulness).toList())))
                .avgLatencyMs(round(avg(results.stream().map(item -> item.getLatencyMs() == null ? null : item.getLatencyMs().doubleValue()).toList())))
                .failureRate(round(results.stream().filter(item -> !"SUCCESS".equals(item.getStatus())).count() / totalSafe))
                .build();
    }

    /** Loads a tenant-scoped dataset; throws {@link IllegalArgumentException} when blank or missing. */
    private EvalDatasetRecord requireDataset(String tenant, String datasetId) {
        if (!StringUtils.hasText(datasetId)) {
            throw new IllegalArgumentException("dataset id is required");
        }
        EvalDatasetRecord dataset = evalDatasetMapper.findByTenantAndDatasetId(tenant, datasetId.trim());
        if (dataset == null) {
            throw new IllegalArgumentException("dataset not found");
        }
        return dataset;
    }

    /** Loads a tenant-scoped run; throws {@link IllegalArgumentException} when blank or missing. */
    private EvalRunRecord requireRun(String tenant, String runId) {
        if (!StringUtils.hasText(runId)) {
            throw new IllegalArgumentException("run id is required");
        }
        EvalRunRecord run = evalRunMapper.findByTenantAndRunId(tenant, runId.trim());
        if (run == null) {
            throw new IllegalArgumentException("run not found");
        }
        return run;
    }

    /** Maps a dataset record to its VO, formatting timestamps as ISO local date-time. */
    private EvalDatasetVO toDatasetVO(EvalDatasetRecord record, int caseCount) {
        return EvalDatasetVO.builder()
                .datasetId(record.getDatasetId())
                .tenantId(record.getTenantId())
                .name(record.getName())
                .description(record.getDescription())
                .baselineRunId(record.getBaselineRunId())
                .caseCount(caseCount)
                .createdAt(formatTime(record.getCreatedAt()))
                .updatedAt(formatTime(record.getUpdatedAt()))
                .build();
    }

    /** Maps a run record plus its results to the API VO, with null metrics degraded to zero. */
    private EvalRunVO toRunVO(EvalRunRecord run, List<EvalResultRecord> results) {
        EvalMetricSummaryVO metrics = EvalMetricSummaryVO.builder()
                .totalCases(intOrZero(run.getTotalCases()))
                .passedCases(intOrZero(run.getPassedCases()))
                .runScore(valueOrZero(run.getRunScore()))
                .retrievalHitRate(valueOrZero(run.getRetrievalHitRate()))
                .citationCoverageRate(valueOrZero(run.getCitationCoverageRate()))
                .answerFaithfulnessScore(valueOrZero(run.getAnswerFaithfulnessScore()))
                .avgLatencyMs(valueOrZero(run.getAvgLatencyMs()))
                .failureRate(valueOrZero(run.getFailureRate()))
                .build();
        return EvalRunVO.builder()
                .runId(run.getRunId())
                .datasetId(run.getDatasetId())
                .tenantId(run.getTenantId())
                .status(run.getStatus())
                .modelProfile(run.getModelProfile())
                .metrics(metrics)
                .results(results == null ? List.of() : results.stream().map(this::toResultVO).toList())
                .errorMessage(run.getErrorMessage())
                .startedAt(formatTime(run.getStartedAt()))
                .finishedAt(formatTime(run.getFinishedAt()))
                .createdAt(formatTime(run.getCreatedAt()))
                .build();
    }

    /** Maps one result record to its VO, deserializing the stored citation/evidence JSON lists. */
    private EvalResultVO toResultVO(EvalResultRecord record) {
        return EvalResultVO.builder()
                .resultId(record.getResultId())
                .caseId(record.getCaseId())
                .status(record.getStatus())
                .question(record.getQuestionText())
                .answer(record.getAnswerText())
                .citations(readJsonList(record.getCitationsJson()))
                .evidence(readJsonList(record.getEvidenceJson()))
                .retrievalHit(valueOrZero(record.getRetrievalHit()))
                .citationCoverage(valueOrZero(record.getCitationCoverage()))
                .keywordScore(valueOrZero(record.getKeywordScore()))
                .answerFaithfulness(valueOrZero(record.getAnswerFaithfulness()))
                .score(valueOrZero(record.getScore()))
                .latencyMs(record.getLatencyMs() == null ? 0 : record.getLatencyMs())
                .errorMessage(record.getErrorMessage())
                .build();
    }

    /**
     * Picks the chat/conversation id used for a case: the case's own chatId,
     * else {@code <prefix>-NNN} from the request, else the dataset id.
     */
    private String resolveChatId(EvalCaseRecord evalCase, EvalRunRequestVO request, int index) {
        if (StringUtils.hasText(evalCase.getChatId())) {
            return evalCase.getChatId().trim();
        }
        if (request != null && StringUtils.hasText(request.getChatIdPrefix())) {
            return request.getChatIdPrefix().trim() + "-" + String.format(Locale.ROOT, "%03d", index + 1);
        }
        return evalCase.getDatasetId();
    }

    /** Flattens citation items to deduplicated {@code sourceType:title:chunkId} strings, skipping empty ones. */
    private List<String> toCitationStrings(List<CitationItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (CitationItem item : items) {
            if (item == null) {
                continue;
            }
            String text = "%s:%s:%s".formatted(
                    emptyIfBlank(item.getSourceType()),
                    emptyIfBlank(item.getTitle()),
                    emptyIfBlank(item.getChunkId())
            );
            if (StringUtils.hasText(text.replace(":", ""))) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    /** Extracts non-blank snippets from evidence items, in order. */
    private List<String> toEvidenceStrings(List<EvidenceItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (EvidenceItem item : items) {
            if (item != null && StringUtils.hasText(item.getSnippet())) {
                values.add(item.getSnippet());
            }
        }
        return values;
    }

    /** Null-skipping mean; 0.0 for an empty or all-null list. */
    private double avg(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (Double value : values) {
            if (value == null) {
                continue;
            }
            sum += value;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * Serializes a string list for a JSON column; serialization failures are
     * logged with their cause and degrade to {@code "[]"}.
     */
    private String writeJsonList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException ex) {
            log.warn("failed to serialize json list, storing [] instead: {}", describeCause(ex), ex);
            return "[]";
        }
    }

    /** Deserializes a stored JSON list; blank or corrupt values are logged and return an empty list. */
    private List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            log.warn("failed to read stored json list, returning [] instead: {}", describeCause(ex), ex);
            return List.of();
        }
    }

    /** Null-safe {@code Double} to primitive conversions used by the VO mappers. */
    private double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /** See {@link #valueOrZero}. */
    private int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** Rounds to 4 decimal places for stable metric display. */
    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** ISO local date-time rendering; empty string for null. */
    private String formatTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 12-char hex id suffix for dataset / run / result ids. */
    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** {@return value if it has text, otherwise the empty string} */
    private String emptyIfBlank(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
