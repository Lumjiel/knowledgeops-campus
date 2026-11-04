// @vitest-environment jsdom
/**
 * EvaluationPanel.vue 的组件级单元测试（Vitest + @vue/test-utils + jsdom）。
 *
 * 环境说明（2026-11-04）：vitest / @vue/test-utils / jsdom 尚未进入 package.json
 * 且本环境禁止 npm install，本文件当前无法在本地执行；补齐依赖后由 CI 运行。
 *
 * Mock 策略：
 * - `api/client` 的六个评测 API 整体 mock（组件的全部外部 I/O）；element-plus 只保留
 *   ElMessage，模板中的 el-* 组件用 global.components 注册轻量替身（el-table 语义
 *   太重，替身只渲染 empty-text，行内容不测——结果行数据经 expose 的 currentRun 断言）。
 * - utils/errors、utils/session、composables/useGuestRestriction 用真实实现：
 *   游客 403 → 引导卡片的模块级单例状态可直接断言（测试间用 dismissGuestRestriction 复位）。
 *
 * 覆盖面：按需加载与去重、数据集列表渲染与选中（prop 驱动 active class、点击 emit）、
 * 六张指标卡的格式化与升降好坏判定、run 摘要与空态、创建评测集（按钮禁用逻辑、
 * 三种 JSON 形态、snake_case 归一化、非法 JSON 与空 case 的错误出口）、游客 403 引导、
 * 运行评测 / 设基线 / 导出报告（expose 方法，含无 run 时的 no-op 与失败路径）。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { ElMessage } from 'element-plus';
import {
  createEvalDataset,
  exportEvalRunReport,
  getEvalComparison,
  listEvalDatasets,
  markEvalRunBaseline,
  triggerEvalRun,
} from '../../api/client';
import EvaluationPanel from '../EvaluationPanel.vue';
import { FriendlyError } from '../../utils/errors';
import { dismissGuestRestriction, useGuestRestriction } from '../../composables/useGuestRestriction';
import type {
  AuthContext,
  EvalComparison,
  EvalDataset,
  EvalMetricSummary,
  EvalRun,
} from '../../types/react';

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
}));
vi.mock('../../api/client', () => ({
  createEvalDataset: vi.fn(),
  exportEvalRunReport: vi.fn(),
  getEvalComparison: vi.fn(),
  listEvalDatasets: vi.fn(),
  markEvalRunBaseline: vi.fn(),
  triggerEvalRun: vi.fn(),
}));

const successMock = vi.mocked(ElMessage.success);
const errorMock = vi.mocked(ElMessage.error);

// ── el-* 轻量替身 ─────────────────────────────────────────────

const ElInputStub = defineComponent({
  name: 'ElInput',
  inheritAttrs: false,
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: `<input v-bind="$attrs" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
});
const ElButtonStub = defineComponent({
  name: 'ElButton',
  inheritAttrs: false,
  props: { loading: { type: Boolean, default: false } },
  emits: ['click'],
  template: `<button v-bind="$attrs" @click="$emit('click', $event)"><slot /></button>`,
});
const ElCollapseStub = defineComponent({
  name: 'ElCollapse',
  template: `<div class="collapse-stub"><slot /></div>`,
});
const ElCollapseItemStub = defineComponent({
  name: 'ElCollapseItem',
  template: `<div class="collapse-item-stub"><slot name="title" /><slot /></div>`,
});
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  template: `<div style="display:none"></div>`,
});
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] }, emptyText: { type: String, default: '' } },
  template: `<div class="table-stub"><p v-if="!data.length" class="table-empty-text">{{ emptyText }}</p></div>`,
});

// ── 装配与夹具 ────────────────────────────────────────────────

/** 组件默认 props：真实 authContext getter（apiKey=key-1）+ balanced 档位，未选中数据集、非游客。 */
function baseProps() {
  return {
    authContext: () => ({ apiKey: 'key-1' }) as AuthContext,
    modelProfile: 'balanced',
    selectedDatasetId: '',
    guestMode: false,
  };
}

// 已挂载的 wrapper 收集队列，afterEach 统一卸载，避免用例间残留全局状态。
const wrappers: VueWrapper[] = [];

/** 以默认 props + el-* 替身挂载组件，props 覆盖项合并进 baseProps，wrapper 记入统一卸载队列。 */
function mountPanel(props: Partial<ReturnType<typeof baseProps>> = {}): VueWrapper {
  const wrapper = mount(EvaluationPanel, {
    props: { ...baseProps(), ...props },
    global: {
      components: {
        ElCollapse: ElCollapseStub,
        ElCollapseItem: ElCollapseItemStub,
        ElInput: ElInputStub,
        ElButton: ElButtonStub,
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
      },
    },
  });
  wrappers.push(wrapper);
  return wrapper;
}

/** 以可索引视图取组件实例，便于断言 expose 的内部状态（datasets、currentRun、loading 等）。 */
function vmOf(wrapper: VueWrapper): Record<string, unknown> {
  return wrapper.vm as unknown as Record<string, unknown>;
}

/** 按可见文案查找按钮（el-button 替身渲染为原生 button），找不到即断言失败。 */
function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text().trim() === text);
  expect(button, `should render a button labelled ${text}`).toBeDefined();
  return button!;
}

/** EvalDataset 夹具：固定 tenantId=t1、caseCount=3 与时间戳，仅 id/名称可变。 */
function makeDataset(datasetId: string, name: string): EvalDataset {
  return {
    datasetId,
    tenantId: 't1',
    name,
    caseCount: 3,
    createdAt: '2026-11-04T10:00:00',
    updatedAt: '2026-11-04T10:00:00',
  };
}

/** 六项指标夹具（默认值即指标卡测试的基线），overrides 覆盖单项以构造与基线的 delta 场景。 */
function metrics(overrides: Partial<EvalMetricSummary> = {}): EvalMetricSummary {
  return {
    totalCases: 3,
    passedCases: 2,
    runScore: 0.85,
    retrievalHitRate: 0.9,
    citationCoverageRate: 0.8,
    answerFaithfulnessScore: 0.7,
    avgLatencyMs: 1200,
    failureRate: 0.1,
    ...overrides,
  };
}

/** finished 状态的 EvalRun 夹具，指标经 metrics() 兜底，results 置空（行数据不渲染、不测）。 */
function makeRun(runId: string, metricOverrides: Partial<EvalMetricSummary> = {}): EvalRun {
  return {
    runId,
    datasetId: 'ds-1',
    tenantId: 't1',
    status: 'finished',
    modelProfile: 'balanced',
    metrics: metrics(metricOverrides),
    results: [],
    createdAt: '2026-11-04T10:05:00',
  };
}

/** 对比响应夹具：固定挂 ds-1 数据集，当前 run 与基线 run 可分别为 null（空态场景）。 */
function makeComparison(current: EvalRun | null, baseline: EvalRun | null): EvalComparison {
  return { dataset: makeDataset('ds-1', '回归集'), current, baseline };
}

/** 加载两个数据集并完成选中/对比的通用前置。 */
async function mountWithDatasets(props: Partial<ReturnType<typeof baseProps>> = {}) {
  vi.mocked(listEvalDatasets).mockResolvedValue([makeDataset('ds-1', '回归集'), makeDataset('ds-2', '演示集')]);
  vi.mocked(getEvalComparison).mockResolvedValue(makeComparison(null, null));
  const wrapper = mountPanel(props);
  (vmOf(wrapper).maybeLoadDatasets as () => void)();
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom 未实现 Blob URL，组件导出报告路径需要
  URL.createObjectURL = vi.fn(() => 'blob:mock');
  URL.revokeObjectURL = vi.fn();
  dismissGuestRestriction();
});

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  dismissGuestRestriction();
});

// ── 数据集加载与列表 ─────────────────────────────────────────

describe('dataset loading', () => {
  it('loads datasets on demand, auto-selects the first and persists', async () => {
    vi.mocked(getEvalComparison).mockResolvedValue(makeComparison(makeRun('run-1'), null));
    const wrapper = await mountWithDatasets();

    expect(listEvalDatasets).toHaveBeenCalledWith(
      expect.objectContaining({ apiKey: 'key-1' }),
    );
    expect(vmOf(wrapper).datasets as EvalDataset[]).toHaveLength(2);
    expect(wrapper.emitted('update:selectedDatasetId')![0]).toEqual(['ds-1']);
    expect(getEvalComparison).toHaveBeenCalledWith('ds-1', expect.anything());
    expect(wrapper.emitted('persist')).toHaveLength(1);
  });

  it('skips reloading while a load is in flight or datasets already exist', async () => {
    let resolveList!: (datasets: EvalDataset[]) => void;
    vi.mocked(listEvalDatasets).mockImplementation(
      () => new Promise<EvalDataset[]>((resolve) => {
        resolveList = resolve;
      }),
    );
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    expect(listEvalDatasets).toHaveBeenCalledTimes(1);

    resolveList([makeDataset('ds-1', '回归集')]);
    await flushPromises();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    expect(listEvalDatasets).toHaveBeenCalledTimes(1);
  });

  it('shows the empty hint and skips the comparison fetch without datasets', async () => {
    vi.mocked(listEvalDatasets).mockResolvedValue([]);
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    expect(wrapper.find('.session-empty').text()).toBe('暂无评测集');
    expect(wrapper.emitted('update:selectedDatasetId')).toBeUndefined();
    expect(getEvalComparison).not.toHaveBeenCalled();
    expect(wrapper.emitted('persist')).toHaveLength(1);
  });

  it('reports load failures through the unified error channel and resets loading', async () => {
    vi.mocked(listEvalDatasets).mockRejectedValue(
      new FriendlyError(500, '服务暂时不可用，请稍后重试'),
    );
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('评测集加载失败：服务暂时不可用，请稍后重试');
    expect(vmOf(wrapper).loading).toBe(false);
  });
});

describe('dataset list rendering', () => {
  it('renders entries with name, case count and truncated short id', async () => {
    // 用超过 10 位的数据集 id 验证 shortId 截断
    vi.mocked(listEvalDatasets).mockResolvedValue([
      makeDataset('ds-1', '回归集'),
      makeDataset('ds-1234567890abcdef', '演示集'),
    ]);
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    expect(wrapper.find('.eval-panel-head strong').text()).toBe('2');
    const buttons = wrapper.findAll('.eval-dataset-list button');
    expect(buttons).toHaveLength(2);
    expect(buttons[0].text()).toContain('回归集');
    expect(buttons[0].text()).toContain('3 cases');
    expect(buttons[1].text()).toContain('ds-1234567'); // 截取前 10 位
    expect(buttons[1].text()).not.toContain('ds-1234567890abcdef');
  });

  it('marks the dataset matching the selectedDatasetId prop as active', async () => {
    const wrapper = await mountWithDatasets({ selectedDatasetId: 'ds-2' });

    const buttons = wrapper.findAll('.eval-dataset-list button');
    expect(buttons[1].classes()).toContain('active');
    expect(buttons[0].classes()).not.toContain('active');
  });

  it('emits the selection, persists and reloads the comparison on click', async () => {
    const wrapper = await mountWithDatasets();
    vi.mocked(getEvalComparison).mockClear();
    const buttons = wrapper.findAll('.eval-dataset-list button');

    await buttons[1].trigger('click');
    await flushPromises();

    expect(wrapper.emitted('update:selectedDatasetId')![0]).toEqual(['ds-2']);
    expect(wrapper.emitted('persist')).toHaveLength(1);
    expect(getEvalComparison).toHaveBeenCalledWith('ds-2', expect.anything());
    // 本地镜像同 tick 可读（不依赖父组件回传）
    expect(vmOf(wrapper).selectedDatasetId).toBe('ds-2');

    // active class 由 prop 驱动，父组件写回后生效
    await wrapper.setProps({ selectedDatasetId: 'ds-2' });
    expect(buttons[1].classes()).toContain('active');
  });

  it('mirrors external selectedDatasetId changes into the local ref', async () => {
    const wrapper = mountPanel({ selectedDatasetId: 'ds-1' });
    expect(vmOf(wrapper).selectedDatasetId).toBe('ds-1');

    await wrapper.setProps({ selectedDatasetId: 'ds-2' });
    expect(vmOf(wrapper).selectedDatasetId).toBe('ds-2');
  });
});

// ── 指标卡与 run 摘要 ────────────────────────────────────────

describe('metric cards and run summaries', () => {
  it('renders six cards with percent/ms formatting and signed good/bad deltas', async () => {
    vi.mocked(listEvalDatasets).mockResolvedValue([makeDataset('ds-1', '回归集')]);
    vi.mocked(getEvalComparison).mockResolvedValue(
      makeComparison(
        makeRun('run-current'),
        makeRun('run-baseline', { runScore: 0.8, avgLatencyMs: 1000, failureRate: 0.12 }),
      ),
    );
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    const cards = wrapper.findAll('.eval-metric-card');
    expect(cards).toHaveLength(6);
    expect(cards[0].text()).toContain('总评分');
    expect(cards[0].text()).toContain('85.0%');
    expect(cards[0].text()).toContain('+5.0%');
    expect(cards[0].find('small').classes()).toContain('good');

    // 平均延迟：升高 200ms → 展示 +200ms 但判为坏（lower is better）
    expect(cards[4].text()).toContain('平均延迟');
    expect(cards[4].text()).toContain('1200ms');
    expect(cards[4].text()).toContain('+200ms');
    expect(cards[4].find('small').classes()).toContain('bad');

    // 失败率：下降 2 个百分点 → -2.0% 判为好
    expect(cards[5].text()).toContain('失败率');
    expect(cards[5].text()).toContain('-2.0%');
    expect(cards[5].find('small').classes()).toContain('good');

    // run 摘要：基线与当前的 runId + 百分比
    const summaries = wrapper.findAll('.eval-run-summary');
    expect(summaries[0].text()).toContain('run-baseline');
    expect(summaries[0].text()).toContain('80.0%');
    expect(summaries[1].text()).toContain('run-current');
    expect(summaries[1].text()).toContain('85.0%');
    expect(summaries[1].classes()).toContain('current');
  });

  it('renders placeholders when no comparison has been loaded', () => {
    const wrapper = mountPanel();

    const cards = wrapper.findAll('.eval-metric-card');
    expect(cards[0].text()).toContain('总评分');
    expect(cards[0].text()).toContain('baseline -');
    expect(cards[0].find('small').classes()).toContain('neutral');

    const summaries = wrapper.findAll('.eval-run-summary');
    expect(summaries[0].text()).toContain('none');
    expect(summaries[0].text()).toContain('-');
    expect(wrapper.find('.table-empty-text').text()).toBe('暂无评测结果');
  });
});

// ── 创建评测集：JSON 解析与错误出口 ───────────────────────────

describe('create dataset', () => {
  it('disables the create button until name and json are both filled', async () => {
    const wrapper = mountPanel();
    const create = buttonByText(wrapper, '创建评测集');
    // 默认名与默认 JSON 均非空 → 可点
    expect(create.attributes('disabled')).toBeUndefined();

    const nameInput = wrapper.find('input[placeholder="数据集名称"]');
    await nameInput.setValue('   ');
    expect(create.attributes('disabled')).toBeDefined();

    await nameInput.setValue('新集合');
    expect(create.attributes('disabled')).toBeUndefined();
    await wrapper.find('input.eval-json-input').setValue('   ');
    expect(create.attributes('disabled')).toBeDefined();
  });

  it('creates from the default json, prepends and selects the new dataset', async () => {
    vi.mocked(createEvalDataset).mockResolvedValue(makeDataset('ds-new', 'RAG Evaluation Studio Demo'));
    const wrapper = mountPanel();

    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(createEvalDataset).toHaveBeenCalledTimes(1);
    const [request] = vi.mocked(createEvalDataset).mock.calls[0];
    expect(request.name).toBe('RAG Evaluation Studio Demo');
    expect(request.description).toBe('RAG baseline regression set');
    expect(request.cases).toHaveLength(3);
    expect(request.cases[0]).toMatchObject({
      caseId: 'rag_001',
      category: 'rag_recall',
      question: '根据知识库，课程预约需要哪些字段？',
      expectedKeywords: ['课程', '姓名', '联系方式', '校区'],
      forbiddenKeywords: ['我不知道', '无法回答'],
    });
    expect(request.cases[2].expectedCitations).toEqual(['heat']);

    expect((vmOf(wrapper).datasets as EvalDataset[])[0].datasetId).toBe('ds-new');
    expect(wrapper.emitted('update:selectedDatasetId')).toContainEqual(['ds-new']);
    expect(wrapper.emitted('persist')).toBeTruthy();
    expect(successMock).toHaveBeenCalledWith('评测集已创建');
  });

  it('accepts the { cases: [...] } object shape', async () => {
    vi.mocked(createEvalDataset).mockResolvedValue(makeDataset('ds-new', '新集合'));
    const wrapper = mountPanel();

    await wrapper.find('input.eval-json-input').setValue(
      JSON.stringify({ cases: [{ caseId: 'c1', question: '问题一' }] }),
    );
    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(createEvalDataset).toHaveBeenCalledTimes(1);
    expect(vi.mocked(createEvalDataset).mock.calls[0][0].cases).toMatchObject([
      { caseId: 'c1', question: '问题一' },
    ]);
  });

  it('flattens OpenAPI paths shapes into cases', async () => {
    vi.mocked(createEvalDataset).mockResolvedValue(makeDataset('ds-new', '新集合'));
    const wrapper = mountPanel();

    await wrapper.find('input.eval-json-input').setValue(
      JSON.stringify({
        paths: {
          '/x': { cases: [{ caseId: 'c1', question: 'q1' }] },
          '/y': {},
        },
      }),
    );
    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(vi.mocked(createEvalDataset).mock.calls[0][0].cases).toHaveLength(1);
    expect(vi.mocked(createEvalDataset).mock.calls[0][0].cases[0].caseId).toBe('c1');
  });

  it('normalizes snake_case fields and filters cases without a question', async () => {
    vi.mocked(createEvalDataset).mockResolvedValue(makeDataset('ds-new', '新集合'));
    const wrapper = mountPanel();

    await wrapper.find('input.eval-json-input').setValue(
      JSON.stringify([
        {
          id: 'c1',
          chat_id: 'chat-x',
          question: ' 问题一 ',
          expected_keywords: ['k1'],
          forbidden_keywords: ['f1'],
          expected_citations: ['src'],
        },
        { caseId: 'c2', question: '' },
        { caseId: 'c3', question: '问题三', expectedKeywords: 'not-an-array' },
      ]),
    );
    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    const cases = vi.mocked(createEvalDataset).mock.calls[0][0].cases;
    expect(cases).toHaveLength(2);
    expect(cases[0]).toMatchObject({
      caseId: 'c1',
      category: 'rag', // 缺省 category 兜底
      chatId: 'chat-x',
      question: '问题一',
      expectedKeywords: ['k1'],
      forbiddenKeywords: ['f1'],
      expectedCitations: ['src'],
    });
    expect(cases[1]).toMatchObject({ caseId: 'c3', question: '问题三', expectedKeywords: [] });
  });

  it('rejects malformed json with the bare fallback message and skips the API', async () => {
    const wrapper = mountPanel();

    await wrapper.find('input.eval-json-input').setValue('not json');
    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    // SyntaxError 文案为英文 → toUserMessage 只返回操作上下文兜底
    expect(errorMock).toHaveBeenCalledWith('评测集创建失败');
    expect(createEvalDataset).not.toHaveBeenCalled();
  });

  it('rejects json without usable cases with a readable message', async () => {
    const wrapper = mountPanel();

    await wrapper.find('input.eval-json-input').setValue('[]');
    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('评测集创建失败：评测集 JSON 没有可用 case');
    expect(createEvalDataset).not.toHaveBeenCalled();
  });
});

// ── 游客 403 引导 ────────────────────────────────────────────

describe('guest restriction routing', () => {
  it('routes guest 403 rejections to the restriction card without a bare error', async () => {
    vi.mocked(createEvalDataset).mockRejectedValue(
      new FriendlyError(403, '当前账号没有执行此操作的权限'),
    );
    const wrapper = mountPanel({ guestMode: true });

    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(errorMock).not.toHaveBeenCalled();
    const { restriction } = useGuestRestriction();
    expect(restriction.value).toMatchObject({ feature: '检索评测' });
  });

  it('keeps the bare error for guests when the status is not 403', async () => {
    vi.mocked(createEvalDataset).mockRejectedValue(
      new FriendlyError(500, '服务暂时不可用，请稍后重试'),
    );
    const wrapper = mountPanel({ guestMode: true });

    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('评测集创建失败：服务暂时不可用，请稍后重试');
    const { restriction } = useGuestRestriction();
    expect(restriction.value).toBeNull();
  });

  it('keeps the bare error for 403 when guest mode is off', async () => {
    vi.mocked(createEvalDataset).mockRejectedValue(
      new FriendlyError(403, '当前账号没有执行此操作的权限'),
    );
    const wrapper = mountPanel({ guestMode: false });

    await buttonByText(wrapper, '创建评测集').trigger('click');
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('评测集创建失败：当前账号没有执行此操作的权限');
  });
});

// ── expose 方法：运行评测 / 设基线 / 导出报告 ─────────────────

describe('run / baseline / report actions', () => {
  it('runSelected triggers a run and swaps it in as the current run', async () => {
    const run = makeRun('run-2', { totalCases: 3 });
    vi.mocked(triggerEvalRun).mockResolvedValue(run);
    const wrapper = mountPanel({ selectedDatasetId: 'ds-1' });

    await (vmOf(wrapper).runSelected as () => Promise<void>)();
    await flushPromises();

    expect(triggerEvalRun).toHaveBeenCalledWith(
      'ds-1',
      { modelProfile: 'balanced', chatIdPrefix: 'eval-studio' },
      expect.anything(),
    );
    expect((vmOf(wrapper).currentRun as EvalRun | null)?.runId).toBe('run-2');
    expect(successMock).toHaveBeenCalledWith('评测完成');
  });

  it('runSelected is a no-op without a selected dataset', async () => {
    const wrapper = mountPanel();

    await (vmOf(wrapper).runSelected as () => Promise<void>)();

    expect(triggerEvalRun).not.toHaveBeenCalled();
  });

  it('runSelected reports failures and resets the running flag', async () => {
    vi.mocked(triggerEvalRun).mockRejectedValue(
      new FriendlyError(429, '操作太频繁，请稍等片刻再试'),
    );
    const wrapper = mountPanel({ selectedDatasetId: 'ds-1' });

    await (vmOf(wrapper).runSelected as () => Promise<void>)();
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('评测运行失败：操作太频繁，请稍等片刻再试');
    expect(vmOf(wrapper).running).toBe(false);
  });

  it('markBaseline marks the current run and reloads the comparison', async () => {
    vi.mocked(listEvalDatasets).mockResolvedValue([makeDataset('ds-1', '回归集')]);
    vi.mocked(getEvalComparison).mockResolvedValue(makeComparison(makeRun('run-1'), null));
    vi.mocked(markEvalRunBaseline).mockResolvedValue(makeRun('run-1'));
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    await (vmOf(wrapper).markBaseline as () => Promise<void>)();
    await flushPromises();

    expect(markEvalRunBaseline).toHaveBeenCalledWith('run-1', expect.anything());
    expect(getEvalComparison).toHaveBeenCalledTimes(2); // 初次加载 + 基线后重载
    expect(successMock).toHaveBeenCalledWith('Baseline 已更新');
  });

  it('markBaseline is a no-op without a current run', async () => {
    const wrapper = mountPanel();

    await (vmOf(wrapper).markBaseline as () => Promise<void>)();

    expect(markEvalRunBaseline).not.toHaveBeenCalled();
  });

  it('exportReport downloads the report via a blob link for the current run', async () => {
    vi.mocked(listEvalDatasets).mockResolvedValue([makeDataset('ds-1', '回归集')]);
    vi.mocked(getEvalComparison).mockResolvedValue(makeComparison(makeRun('run-1'), null));
    vi.mocked(exportEvalRunReport).mockResolvedValue('# 报告');
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    await (vmOf(wrapper).exportReport as () => Promise<void>)();
    await flushPromises();

    expect(exportEvalRunReport).toHaveBeenCalledWith('run-1', expect.anything());
    expect(URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock');
    expect(successMock).toHaveBeenCalledWith('报告已导出');
    expect(vmOf(wrapper).exporting).toBe(false);
  });

  it('exportReport is a no-op without a current run', async () => {
    const wrapper = mountPanel();

    await (vmOf(wrapper).exportReport as () => Promise<void>)();

    expect(exportEvalRunReport).not.toHaveBeenCalled();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('exportReport reports failures and resets the exporting flag', async () => {
    vi.mocked(listEvalDatasets).mockResolvedValue([makeDataset('ds-1', '回归集')]);
    vi.mocked(getEvalComparison).mockResolvedValue(makeComparison(makeRun('run-1'), null));
    vi.mocked(exportEvalRunReport).mockRejectedValue(
      new FriendlyError(502, '服务暂时不可用，请稍后重试'),
    );
    const wrapper = mountPanel();
    (vmOf(wrapper).maybeLoadDatasets as () => void)();
    await flushPromises();

    await (vmOf(wrapper).exportReport as () => Promise<void>)();
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('报告导出失败：服务暂时不可用，请稍后重试');
    expect(vmOf(wrapper).exporting).toBe(false);
  });
});
