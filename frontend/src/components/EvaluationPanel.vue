<template>
  <section class="evaluation-page">
    <!-- 左侧：数据集计数/列表（prop 驱动 active）+ 创建评测集折叠面板 -->
    <aside class="eval-side-panel">
      <div class="eval-panel-head">
        <div>
          <p class="section-label">数据集</p>
          <strong>{{ evalDatasets.length }}</strong>
        </div>
      </div>

      <div class="eval-dataset-list">
        <button
          v-for="dataset in evalDatasets"
          :key="dataset.datasetId"
          type="button"
          :class="{ active: dataset.datasetId === selectedDatasetId }"
          @click="selectEvalDataset(dataset.datasetId)"
        >
          <span>{{ dataset.name }}</span>
          <small>{{ dataset.caseCount }} cases · {{ shortId(dataset.datasetId) }}</small>
        </button>
        <div v-if="!evalDatasets.length" class="session-empty">暂无评测集</div>
      </div>

      <el-collapse class="eval-create-collapse">
        <el-collapse-item name="create">
          <template #title>
            <span class="branch-collapse-title">＋ 创建评测集</span>
          </template>
          <div class="eval-create-panel">
            <el-input v-model="evalDatasetName" size="small" placeholder="数据集名称" />
            <el-input v-model="evalDatasetDescription" size="small" placeholder="描述" />
            <el-input
              v-model="evalDatasetJson"
              class="eval-json-input"
              type="textarea"
              :rows="11"
              resize="none"
              spellcheck="false"
            />
            <el-button
              type="primary"
              :loading="evalCreating"
              :disabled="!evalDatasetName.trim() || !evalDatasetJson.trim()"
              @click="createEvalDatasetFromJson"
              >创建评测集</el-button
            >
          </div>
        </el-collapse-item>
      </el-collapse>
    </aside>

    <!-- 右侧：六张指标卡、基线/当前 run 摘要、结果明细表 -->
    <section class="eval-main-panel">
      <div class="eval-score-strip">
        <div v-for="metric in evalMetricCards" :key="metric.key" class="eval-metric-card">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.current }}</strong>
          <small :class="metric.deltaClass">{{ metric.delta }}</small>
        </div>
      </div>

      <div class="eval-run-grid">
        <div class="eval-run-summary">
          <p class="section-label">基线</p>
          <strong>{{ evalBaselineRun?.runId || 'none' }}</strong>
          <span>{{ formatRunScore(evalBaselineRun?.metrics.runScore) }}</span>
        </div>
        <div class="eval-run-summary current">
          <p class="section-label">当前</p>
          <strong>{{ evalCurrentRun?.runId || 'none' }}</strong>
          <span>{{ formatRunScore(evalCurrentRun?.metrics.runScore) }}</span>
        </div>
      </div>

      <el-table
        :data="evalCurrentRun?.results ?? []"
        class="eval-result-table"
        height="100%"
        empty-text="暂无评测结果"
      >
        <el-table-column prop="caseId" label="用例" min-width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="得分" width="110">
          <template #default="{ row }">{{ formatPercent(row.score) }}</template>
        </el-table-column>
        <el-table-column label="引用覆盖" width="120">
          <template #default="{ row }">{{ formatPercent(row.citationCoverage) }}</template>
        </el-table-column>
        <el-table-column label="延迟" width="120">
          <template #default="{ row }">{{ row.latencyMs }}ms</template>
        </el-table-column>
        <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
      </el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
/**
 * 评测工作台：数据集列表/创建、评测运行、基线对比、指标卡与报告导出。
 * 从 App.vue 整块拆出；数据集选中值由 App 持久化（v-model:selected-dataset-id），
 * 鉴权上下文与模型档位由 App 传入；顶栏动作按钮通过 defineExpose 暴露的方法驱动。
 */
import { ElMessage } from 'element-plus';
import { computed, ref, watch } from 'vue';
import {
  createEvalDataset,
  exportEvalRunReport,
  getEvalComparison,
  listEvalDatasets,
  markEvalRunBaseline,
  triggerEvalRun,
} from '../api/client';
import { shortId } from '../utils/session';
import { toUserMessage } from '../utils/errors';
import { isGuestForbidden, reportGuestRestriction } from '../composables/useGuestRestriction';
import type {
  AuthContext,
  EvalCaseCreate,
  EvalComparison,
  EvalDataset,
  EvalMetricSummary,
  EvalRun,
} from '../types/react';

/** 指标卡的渲染模型：current/delta 为格式化字符串，deltaClass ∈ good/bad/neutral。 */
interface EvalMetricCard {
  key: keyof EvalMetricSummary;
  label: string;
  current: string;
  delta: string;
  deltaClass: string;
}

/** 内置演示评测集：新建面板的默认 JSON，覆盖 RAG 召回/精度/引用覆盖三类 case。 */
const DEFAULT_EVAL_DATASET = [
  {
    caseId: 'rag_001',
    category: 'rag_recall',
    chatId: 'eval-rag-a',
    question: '根据知识库，课程预约需要哪些字段？',
    expectedKeywords: ['课程', '姓名', '联系方式', '校区'],
    forbiddenKeywords: ['我不知道', '无法回答'],
  },
  {
    caseId: 'rag_002',
    category: 'rag_precision',
    chatId: 'eval-rag-b',
    question: '请总结这个 PDF 里和高温健康风险相关的内容。',
    expectedKeywords: ['高温', '风险'],
    forbiddenKeywords: ['与问题无关', '瞎编'],
  },
  {
    caseId: 'rag_003',
    category: 'citation_coverage',
    chatId: 'eval-rag-b',
    question: '回答时列出引用来源，并说明高温风险处置建议。',
    expectedKeywords: ['引用', '高温', '风险'],
    expectedCitations: ['heat'],
  },
];

const props = defineProps<{
  authContext: () => AuthContext;
  modelProfile: string;
  selectedDatasetId: string;
  /** 游客模式：评测相关 403 时用引导卡片替换裸错误提示 */
  guestMode: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:selectedDatasetId', value: string): void;
  (e: 'persist'): void;
}>();

/** 评测动作统一错误出口：游客 403 走「功能受限」引导卡片，其余保持裸错误文案。 */
function handleEvalError(action: string, error: unknown): void {
  if (isGuestForbidden(error, props.guestMode)) {
    reportGuestRestriction('检索评测');
    return;
  }
  ElMessage.error(toUserMessage(error, action));
}

const evalDatasets = ref<EvalDataset[]>([]);
const evalComparison = ref<EvalComparison | null>(null);
const evalLoading = ref(false);
const evalCreating = ref(false);
const evalRunning = ref(false);
const evalReportExporting = ref(false);
const evalDatasetName = ref('RAG Evaluation Studio Demo');
const evalDatasetDescription = ref('RAG baseline regression set');
const evalDatasetJson = ref(JSON.stringify(DEFAULT_EVAL_DATASET, null, 2));

// 选中值本地镜像：props 回传存在渲染时序延迟，本地 ref 保证赋值后同 tick 可读
// （与拆分前 App.vue 的普通 ref 行为一致）。
const evalSelectedDatasetId = ref(props.selectedDatasetId);

watch(
  () => props.selectedDatasetId,
  (value) => {
    if (value !== evalSelectedDatasetId.value) {
      evalSelectedDatasetId.value = value;
    }
  },
);

/** 选中值唯一出口：先写本地镜像（同 tick 可读）再 emit 同步父级持久化。 */
function setSelectedDatasetId(value: string): void {
  evalSelectedDatasetId.value = value;
  emit('update:selectedDatasetId', value);
}

const selectedEvalDataset = computed(() =>
  evalDatasets.value.find((dataset) => dataset.datasetId === evalSelectedDatasetId.value),
);

const evalCurrentRun = computed<EvalRun | null>(() => evalComparison.value?.current ?? null);

const evalBaselineRun = computed<EvalRun | null>(() => evalComparison.value?.baseline ?? null);

/** 六张指标卡：当前值 + 与基线的 delta；平均延迟/失败率按 lower-is-better 判好坏。 */
const evalMetricCards = computed<EvalMetricCard[]>(() => {
  const current = evalCurrentRun.value?.metrics;
  const baseline = evalBaselineRun.value?.metrics;
  return [
    metricCard('runScore', '总评分', current, baseline, 'percent'),
    metricCard('retrievalHitRate', '检索命中', current, baseline, 'percent'),
    metricCard('citationCoverageRate', '引用覆盖', current, baseline, 'percent'),
    metricCard('answerFaithfulnessScore', '忠实度', current, baseline, 'percent'),
    metricCard('avgLatencyMs', '平均延迟', current, baseline, 'ms', true),
    metricCard('failureRate', '失败率', current, baseline, 'percent', true),
  ];
});

/**
 * 构造单张指标卡：缺失当前值或基线值时 delta 显示 `baseline -`（neutral）；
 * diff 带 +/- 号，good/bad 由 lowerIsBetter 决定。
 */
function metricCard(
  key: keyof EvalMetricSummary,
  label: string,
  current: EvalMetricSummary | undefined,
  baseline: EvalMetricSummary | undefined,
  unit: 'percent' | 'ms',
  lowerIsBetter = false,
): EvalMetricCard {
  const currentValue = current?.[key];
  const baselineValue = baseline?.[key];
  const hasCurrent = typeof currentValue === 'number';
  const hasBaseline = typeof baselineValue === 'number';
  let delta = 'baseline -';
  let deltaClass = 'neutral';
  if (hasCurrent && hasBaseline) {
    const diff = currentValue - baselineValue;
    const good = lowerIsBetter ? diff <= 0 : diff >= 0;
    delta = `${diff >= 0 ? '+' : ''}${formatMetricValue(diff, unit)}`;
    deltaClass = good ? 'good' : 'bad';
  }
  return {
    key,
    label,
    current: hasCurrent ? formatMetricValue(currentValue, unit) : '-',
    delta,
    deltaClass,
  };
}

/** 按单位格式化指标值：ms 取整，percent 走 formatPercent。 */
function formatMetricValue(value: number, unit: 'percent' | 'ms'): string {
  if (unit === 'ms') {
    return `${value.toFixed(0)}ms`;
  }
  return formatPercent(value);
}

/** 小数比例 → 一位小数百分比；非数值（含 NaN/undefined）统一显示 `-`。 */
function formatPercent(value: number | undefined): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  return `${(value * 100).toFixed(1)}%`;
}

/** run 摘要行的总分格式化（非数值显示 `-`）。 */
function formatRunScore(value: number | undefined): string {
  return typeof value === 'number' ? formatPercent(value) : '-';
}

/**
 * 单条 case 归一化：兼容 camelCase 与 snake_case 字段（id→caseId、chat_id→chatId 等）；
 * 非数组的关键词/引用字段丢弃为 []，缺省 category 兜底为 rag，字符串一律 trim。
 */
function normalizeEvalCase(raw: Record<string, unknown>, index: number): EvalCaseCreate {
  const expectedKeywords = raw.expectedKeywords ?? raw.expected_keywords;
  const expectedCitations = raw.expectedCitations ?? raw.expected_citations;
  const forbiddenKeywords = raw.forbiddenKeywords ?? raw.forbidden_keywords;
  return {
    caseId: String(raw.caseId ?? raw.id ?? `case-${index + 1}`).trim(),
    category: String(raw.category ?? 'rag').trim(),
    chatId: String(raw.chatId ?? raw.chat_id ?? '').trim(),
    question: String(raw.question ?? '').trim(),
    expectedKeywords: Array.isArray(expectedKeywords) ? expectedKeywords.map(String) : [],
    expectedCitations: Array.isArray(expectedCitations) ? expectedCitations.map(String) : [],
    forbiddenKeywords: Array.isArray(forbiddenKeywords) ? forbiddenKeywords.map(String) : [],
  };
}

/**
 * 解析创建面板的评测集 JSON，支持三种形态：裸数组、{ cases: [...] }、
 * OpenAPI 风格 { paths: { '/x': { cases: [...] } } }；无 question 的 case 会被过滤，
 * 过滤后为空则抛错（文案经 handleEvalError 的 action 拼接展示）。
 */
function parseEvalDatasetJson(): EvalCaseCreate[] {
  const parsed = JSON.parse(evalDatasetJson.value) as unknown;
  let rawCases: unknown[] = [];
  if (Array.isArray(parsed)) {
    rawCases = parsed;
  } else if (parsed && typeof parsed === 'object') {
    const objectValue = parsed as Record<string, unknown>;
    if (Array.isArray(objectValue.cases)) {
      rawCases = objectValue.cases;
    } else if (objectValue.paths && typeof objectValue.paths === 'object') {
      Object.values(objectValue.paths as Record<string, unknown>).forEach((pathValue) => {
        if (pathValue && typeof pathValue === 'object') {
          const cases = (pathValue as Record<string, unknown>).cases;
          if (Array.isArray(cases)) {
            rawCases.push(...cases);
          }
        }
      });
    }
  }

  const cases = rawCases
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(normalizeEvalCase)
    .filter((item) => item.question);
  if (cases.length === 0) {
    throw new Error('评测集 JSON 没有可用 case');
  }
  return cases;
}

/** 全量加载数据集：无选中值时自动选中首个，随后拉取对比数据并请求父级持久化。 */
async function loadEvalDatasets(): Promise<void> {
  evalLoading.value = true;
  try {
    evalDatasets.value = await listEvalDatasets(props.authContext());
    if (!evalSelectedDatasetId.value && evalDatasets.value.length > 0) {
      setSelectedDatasetId(evalDatasets.value[0].datasetId);
    }
    if (evalSelectedDatasetId.value) {
      await loadEvalComparison(evalSelectedDatasetId.value);
    }
    emit('persist');
  } catch (error) {
    handleEvalError('评测集加载失败', error);
  } finally {
    evalLoading.value = false;
  }
}

/** 进入评测视图时按需加载（与拆分前 App.vue 的守卫一致）。 */
function maybeLoadDatasets(): void {
  if (evalDatasets.value.length === 0 && !evalLoading.value) {
    void loadEvalDatasets();
  }
}

/** 拉取指定数据集的基线/当前 run 对比；失败时清空 comparison（UI 回到空态）。 */
async function loadEvalComparison(datasetId: string): Promise<void> {
  try {
    evalComparison.value = await getEvalComparison(datasetId, props.authContext());
  } catch (error) {
    evalComparison.value = null;
    handleEvalError('评测结果加载失败', error);
  }
}

/** 点击数据集列表项：更新选中值并持久化，再重载该数据集的对比。 */
async function selectEvalDataset(datasetId: string): Promise<void> {
  setSelectedDatasetId(datasetId);
  emit('persist');
  await loadEvalComparison(datasetId);
}

/** 按表单+JSON 创建评测集：新集合插队列表首位并自动选中，再拉取其（空）对比。 */
async function createEvalDatasetFromJson(): Promise<void> {
  evalCreating.value = true;
  try {
    const created = await createEvalDataset(
      {
        name: evalDatasetName.value.trim(),
        description: evalDatasetDescription.value.trim(),
        cases: parseEvalDatasetJson(),
      },
      props.authContext(),
    );
    evalDatasets.value = [created, ...evalDatasets.value];
    setSelectedDatasetId(created.datasetId);
    await loadEvalComparison(created.datasetId);
    emit('persist');
    ElMessage.success('评测集已创建');
  } catch (error) {
    handleEvalError('评测集创建失败', error);
  } finally {
    evalCreating.value = false;
  }
}

/**
 * 运行当前选中数据集的评测：以 chatIdPrefix=eval-studio 触发，完成后将返回的 run
 * 替换为对比中的 current（保留原 baseline；数据集元信息缺失时用 run 字段合成兜底）。
 */
async function runSelectedEvalDataset(): Promise<void> {
  if (!evalSelectedDatasetId.value) {
    return;
  }
  evalRunning.value = true;
  try {
    const run = await triggerEvalRun(
      evalSelectedDatasetId.value,
      {
        modelProfile: props.modelProfile,
        chatIdPrefix: 'eval-studio',
      },
      props.authContext(),
    );
    evalComparison.value = {
      dataset: selectedEvalDataset.value ??
        evalComparison.value?.dataset ?? {
          datasetId: run.datasetId,
          tenantId: run.tenantId,
          name: run.datasetId,
          caseCount: run.metrics.totalCases,
          createdAt: run.createdAt,
          updatedAt: run.createdAt,
        },
      baseline: evalComparison.value?.baseline ?? null,
      current: run,
    };
    ElMessage.success('评测完成');
  } catch (error) {
    handleEvalError('评测运行失败', error);
  } finally {
    evalRunning.value = false;
  }
}

/** 将当前 run 设为基线，成功后重载对比使基线卡刷新（无 current run 时 no-op）。 */
async function markCurrentEvalRunBaseline(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  try {
    await markEvalRunBaseline(run.runId, props.authContext());
    await loadEvalComparison(run.datasetId);
    ElMessage.success('Baseline 已更新');
  } catch (error) {
    handleEvalError('基线更新失败', error);
  }
}

/**
 * 导出当前 run 的 Markdown 报告：Blob URL + 隐藏 <a download> 触发浏览器下载，
 * 用后立即 revoke（无 current run 时 no-op）。
 */
async function downloadEvalReport(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  evalReportExporting.value = true;
  try {
    const report = await exportEvalRunReport(run.runId, props.authContext());
    const blob = new Blob([report], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `rag-evaluation-${run.runId}.md`;
    link.click();
    URL.revokeObjectURL(url);
    ElMessage.success('报告已导出');
  } catch (error) {
    handleEvalError('报告导出失败', error);
  } finally {
    evalReportExporting.value = false;
  }
}

// 顶栏动作按钮经此驱动方法；状态（datasets/currentRun/loading 等）一并暴露供测试断言。
defineExpose({
  loadDatasets: loadEvalDatasets,
  maybeLoadDatasets,
  runSelected: runSelectedEvalDataset,
  exportReport: downloadEvalReport,
  markBaseline: markCurrentEvalRunBaseline,
  datasets: evalDatasets,
  currentRun: evalCurrentRun,
  selectedDataset: selectedEvalDataset,
  selectedDatasetId: evalSelectedDatasetId,
  loading: evalLoading,
  running: evalRunning,
  exporting: evalReportExporting,
});
</script>

<style scoped>
.section-label {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

.session-empty {
  font-size: 12px;
  color: var(--ui-muted);
  padding: 6px;
}

.branch-collapse-title {
  font-size: 12px;
  color: var(--ui-muted);
}

.evaluation-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.eval-side-panel,
.eval-main-panel {
  min-height: 0;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
}

.eval-side-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
}

.eval-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.eval-panel-head strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
}

.eval-dataset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.eval-dataset-list button {
  width: 100%;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  text-align: left;
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
  cursor: pointer;
}

.eval-dataset-list button.active {
  border-color: rgba(15, 118, 110, 0.55);
  box-shadow: inset 0 0 0 1px rgba(15, 118, 110, 0.28);
}

.eval-dataset-list span,
.eval-dataset-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-dataset-list span {
  font-size: 13px;
  font-weight: 700;
}

.eval-dataset-list small {
  margin-top: 4px;
  color: var(--ui-muted);
}

.eval-create-panel {
  display: grid;
  gap: 8px;
}

.eval-json-input :deep(.el-textarea__inner) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}

.eval-main-panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  padding: 12px;
}

.eval-score-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.eval-metric-card {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
  min-width: 0;
}

.eval-metric-card span,
.eval-metric-card small {
  display: block;
  font-size: 11px;
  color: var(--ui-muted);
}

.eval-metric-card strong {
  display: block;
  margin: 7px 0 4px;
  font-size: 22px;
  line-height: 1;
}

.eval-metric-card .good {
  color: #047857;
}

.eval-metric-card .bad {
  color: #dc2626;
}

.eval-metric-card .neutral {
  color: var(--ui-muted);
}

.eval-run-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.eval-run-summary {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 76%, transparent);
}

.eval-run-summary.current {
  border-color: rgba(15, 118, 110, 0.45);
}

.eval-run-summary strong,
.eval-run-summary span {
  display: block;
  margin-top: 6px;
}

.eval-run-summary strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-run-summary span {
  font-size: 22px;
  font-weight: 800;
}

.eval-result-table {
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;
}

/* ── 有章 · 学术风覆盖层（原 App.vue 末尾覆盖段，随模板迁入） ── */
.eval-create-collapse {
  --el-collapse-border-color: transparent;
}

.eval-create-collapse .el-collapse-item__header {
  background: transparent;
  border-bottom: none;
  height: 34px;
}

.eval-side-panel {
  background: transparent;
  border-right: 1px solid var(--ui-border-soft);
}

.eval-dataset-list button {
  border: none;
  border-radius: 9px;
  background: transparent;
}

.eval-dataset-list button.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

.eval-metric-card {
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  background: var(--ui-surface);
  box-shadow: none;
}

.eval-metric-card strong {
  font-variant-numeric: tabular-nums;
}

.eval-run-summary {
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  background: var(--ui-surface);
  box-shadow: none;
}

.eval-run-summary.current {
  border-color: var(--ui-accent-line);
}

.eval-result-table {
  --el-table-border-color: var(--ui-border-soft);
  --el-table-header-text-color: var(--ui-muted);
}

/* ── 响应式 ── */
@media (max-width: 1160px) {
  .eval-score-strip {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }
}

@media (max-width: 980px) {
  .evaluation-page {
    grid-template-columns: 1fr;
  }

  .eval-dataset-list {
    max-height: 180px;
  }
}

@media (max-width: 680px) {
  .evaluation-page {
    padding: 12px;
  }

  .eval-score-strip,
  .eval-run-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  /* 评测页纵向堆叠（原 App.vue 移动端抽屉段） */
  .evaluation-page {
    flex-direction: column;
    overflow-y: auto;
  }

  .eval-side-panel {
    border-right: none;
    border-bottom: 1px solid var(--ui-border-soft);
  }
}
</style>
