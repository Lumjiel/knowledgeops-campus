// @vitest-environment jsdom
/**
 * MessageList.vue 的组件级单元测试（Vitest + @vue/test-utils + jsdom）。
 *
 * 环境说明（2026-11-04）：frontend/package.json 尚未声明 vitest / @vue/test-utils / jsdom
 * 依赖，且本环境禁止 npm install，因此本文件当前无法在本地执行；需在补齐三个
 * devDependencies 后由 CI 运行。文件顶部的 `// @vitest-environment jsdom` 使测试
 * 无需改动 vite.config.ts 即可按文件启用 DOM 环境。
 *
 * Mock 策略：
 * - element-plus 整体 mock（组件仅消费 ElMessage；模板中的 el-input / el-button
 *   通过 global.components 注册轻量替身，避免引入完整 Element Plus）。
 * - navigator.clipboard 用 Object.defineProperty 替身，验证复制成功/失败两条路径。
 * - markdown / DOMPurify / highlight.js 使用真实实现（与生产装配一致），一并覆盖
 *   渲染安全（脚本剥离、reverse-tabnabbing hook、代码块 data-code 载荷）。
 *
 * 覆盖面：hydration 骨架、空会话提示、消息渲染（纯文本/markdown/流式/pending/状态
 * class 回退/时间格式化）、渲染消毒、markdown 配置（breaks/gfm/未知语言回退）、代码块
 * 复制、引用卡片与旧版引用（互斥渲染、展开态、文件名兜底、关键词去重高亮）、证据片段、
 * 动作按钮 emit 与禁用态（含跨消息作用域）、编辑框 v-model 与 emit、检索链路展示、
 * Agent 轨迹时间线（含 observation 两种形态）、虚拟滚动窗口与占位高度。
 */
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { ElMessage } from 'element-plus';
import MessageList from '../MessageList.vue';
import type { ChatMessage } from '../../types/chat';
import type { ReactTraceStep } from '../../types/react';

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
}));

const successMock = vi.mocked(ElMessage.success);
const errorMock = vi.mocked(ElMessage.error);
// 各用例对成功/失败提示的断言均经由这两个 mock 引用进行

// el-input / el-button 的轻量替身：保留 v-model 与点击转发，足够覆盖编辑框交互
const ElInputStub = defineComponent({
  name: 'ElInput',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: `<textarea class="edit-textarea" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)"></textarea>`,
});
const ElButtonStub = defineComponent({
  name: 'ElButton',
  inheritAttrs: false,
  emits: ['click'],
  template: `<button v-bind="$attrs" @click="$emit('click', $event)"><slot /></button>`,
});

/**
 * 构造一组满足组件 Props 契约的最小默认值（空消息列表 + 全部关闭的交互态）。
 * 每次调用返回全新对象（含 new Set / 新 Record），避免用例间共享可变引用。
 * 各用例经 mountList 的 Partial 覆盖需要的字段。
 */
function baseProps() {
  return {
    messages: [] as ChatMessage[],
    hydrating: false,
    isEmptyConversation: false,
    sending: false,
    isStreamingResponse: false,
    streamStatusLabel: '',
    streamStatusDetail: '',
    traceSteps: [] as ReactTraceStep[],
    traceDurationMs: 0,
    expandedCitationCards: new Set<string>(),
    expandedTraceOpen: false,
    editingMessageId: null as string | null,
    editingMessageDraft: '',
    answerFeedbackMap: {} as Record<string, number>,
    answerFeedbackLoading: {} as Record<string, boolean>,
  };
}

/** 当前测试文件内所有已挂载实例；afterEach 统一卸载，防 jsdom 泄漏与告警 */
const wrappers: VueWrapper[] = [];

/**
 * 用默认 props + 覆盖项挂载 MessageList，并注册 ElInput/ElButton 轻量替身。
 * 实例自动登记到 wrappers，由 afterEach 统一卸载。
 * @param props 覆盖默认 props 的字段（深度以 mount 选项 props 为准，仅一层合并）
 */
function mountList(props: Partial<ReturnType<typeof baseProps>> = {}): VueWrapper {
  const wrapper = mount(MessageList, {
    props: { ...baseProps(), ...props },
    global: {
      components: { ElInput: ElInputStub, ElButton: ElButtonStub },
    },
  });
  wrappers.push(wrapper);
  return wrapper;
}

/** 测试夹具：用户消息，时间戳固定为 2026-11-04 10:00:00，保证时间格式化断言确定性 */
function userMessage(id: string, content: string): ChatMessage {
  return { id, role: 'user', content, createdAt: Date.parse('2026-11-04T10:00:00') };
}

/** 测试夹具：助手消息，比用户消息晚 5 秒；state 默认缺省（行 class 回退 done） */
function assistantMessage(id: string, content: string): ChatMessage {
  return { id, role: 'assistant', content, createdAt: Date.parse('2026-11-04T10:00:05') };
}

/**
 * 以可写替身覆盖 navigator.clipboard（jsdom 未实现该 API）。
 * configurable: true 保证每个用例的 beforeEach/afterEach 可重复覆写。
 * @param writeText 注入的写入函数，用 vi.fn() 便于断言调用参数
 */
function stubClipboard(writeText: (text: string) => Promise<void>): void {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
}

/**
 * 按可见文案精确匹配（trim 后全等）查找按钮，找不到时立即让用例失败
 * 并报出期望文案，避免 undefined 上继续断言产生难读的报错。
 */
function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text().trim() === text);
  expect(button, `should render a button labelled ${text}`).toBeDefined();
  return button!;
}

// 每个用例开始前：清空 mock 调用记录，并预置一个默认成功的剪贴板替身
// （需要失败路径的用例在用例内自行覆写为 rejected）
beforeEach(() => {
  vi.clearAllMocks();
  stubClipboard(vi.fn().mockResolvedValue(undefined));
});

// 统一卸载本文件内所有已挂载实例（splice 清空队列），避免跨用例 DOM 残留
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
});

// ── 结构：骨架 / 空会话 / 虚拟滚动 ─────────────────────────────

describe('list structure', () => {
  it('renders the hydration skeleton instead of messages while hydrating', () => {
    const wrapper = mountList({ hydrating: true, messages: [userMessage('u1', '在吗')] });

    expect(wrapper.find('.hydration-skeleton').exists()).toBe(true);
    expect(wrapper.findAll('article')).toHaveLength(0);
    expect(wrapper.find('.empty-hint').exists()).toBe(false);
  });

  it('shows the empty hint for an empty conversation', () => {
    const wrapper = mountList({ isEmptyConversation: true });

    expect(wrapper.find('.empty-hint').exists()).toBe(true);
    expect(wrapper.findAll('article')).toHaveLength(0);
  });

  it('renders rows and virtual spacers inside the overscan window', () => {
    const messages = Array.from({ length: 30 }, (_, i) =>
      i % 2 === 0
        ? userMessage(`m${i}`, `问题 ${i}`)
        : assistantMessage(`m${i}`, `回答 ${i}`),
    );
    const wrapper = mountList({ messages });

    // scrollTop=0、viewportHeight=0（jsdom）：窗口为 [0..8] 共 9 行，其余用占位撑开
    const articles = wrapper.findAll('article');
    expect(articles).toHaveLength(9);
    expect(articles[0].attributes('data-msg-id')).toBe('m0');

    const spacers = wrapper.findAll('.virtual-spacer');
    expect(spacers[0].attributes('style')).toContain('0px');
    // 未渲染的 21 行按估算行高 156 撑起底部占位
    expect(spacers[1].attributes('style')).toContain(`${21 * 156}px`);
  });
});

// ── 消息渲染：纯文本 / markdown / 流式 / pending ───────────────

describe('message rendering', () => {
  it('renders user messages as plain text with user meta', () => {
    const wrapper = mountList({ messages: [userMessage('u1', '如何转专业？')] });

    const row = wrapper.find('article');
    expect(row.classes()).toContain('user');
    expect(row.find('.avatar').text()).toBe('我');
    expect(row.find('.bubble-meta').text()).toContain('你');
    expect(row.find('p.plain').text()).toBe('如何转专业？');
  });

  it('renders completed assistant messages as sanitized markdown', () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '**重点**内容')],
    });

    const row = wrapper.find('article');
    expect(row.classes()).toContain('assistant');
    expect(row.find('.avatar').text()).toBe('章');
    expect(row.find('.bubble-meta').text()).toContain('有章');
    expect(row.find('.markdown').html()).toContain('<strong>重点</strong>');
  });

  it('shows the waiting placeholder for empty assistant content', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', ''), state: 'done' }],
    });

    expect(wrapper.find('.markdown').html()).toContain('等待模型输出...');
  });

  it('renders streaming assistant content as plain text with a status dot', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '**部分**回答'), state: 'streaming' }],
    });

    // 流式期间不转 markdown，原样输出避免逐 token 全量重渲染
    expect(wrapper.find('.streaming-plain').text()).toBe('**部分**回答');
    expect(wrapper.find('.status-dot').text()).toBe('生成中');
    expect(wrapper.find('.markdown').exists()).toBe(false);
  });

  it('renders the assistant skeleton for pending messages without content', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', ''), state: 'pending' }],
    });

    expect(wrapper.find('.assistant-skeleton').exists()).toBe(true);
    expect(wrapper.find('.status-dot').text()).toBe('思考中');
  });

  it('shows the thinking line only while sending a streaming response', () => {
    const idle = mountList({ messages: [assistantMessage('a1', '答案')] });
    expect(idle.find('.thinking').exists()).toBe(false);

    const active = mountList({
      messages: [assistantMessage('a1', '答案')],
      sending: true,
      isStreamingResponse: true,
      streamStatusLabel: '检索中',
      streamStatusDetail: '',
    });
    expect(active.find('.thinking').text()).toContain('检索中 · 处理中...');

    // sending 但非流式响应（如等待首个 token）不显示
    const nonStreaming = mountList({
      messages: [assistantMessage('a1', '答案')],
      sending: true,
      isStreamingResponse: false,
      streamStatusLabel: '检索中',
    });
    expect(nonStreaming.find('.thinking').exists()).toBe(false);
  });

  it('formats message timestamps as HH:mm in the bubble meta', () => {
    const wrapper = mountList({ messages: [userMessage('u1', '在吗')] });

    // zh-CN 2-digit 时分；上午/下午前缀随 ICU 环境不同，只锚定时:分形态
    expect(wrapper.find('.bubble-meta').text()).toMatch(/\d{1,2}:\d{2}/);
  });

  it('falls back to the done row class when state is missing and keeps explicit states', () => {
    const noState = mountList({ messages: [assistantMessage('a1', '答案')] });
    expect(noState.find('article').classes()).toContain('done');

    const errored = mountList({
      messages: [{ ...assistantMessage('a2', '失败'), state: 'error' }],
    });
    expect(errored.find('article').classes()).toContain('error');
  });

  it('renders markdown for pending messages that already carry content', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '**部分**内容'), state: 'pending' }],
    });

    // pending 且非空：不走骨架、不走流式纯文本，直接进 markdown 分支
    expect(wrapper.find('.assistant-skeleton').exists()).toBe(false);
    expect(wrapper.find('.streaming-plain').exists()).toBe(false);
    expect(wrapper.find('.markdown').html()).toContain('<strong>部分</strong>内容');
    expect(wrapper.find('.status-dot').text()).toBe('思考中');
  });
});

// ── markdown 渲染配置：breaks / gfm / 未知语言回退 ─────────────

describe('markdown rendering details', () => {
  it('converts single newlines to <br> (breaks: true)', () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '第一行\n第二行')],
    });

    expect(wrapper.find('.markdown').html()).toContain('<br>');
  });

  it('renders gfm lists', () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '- 项目一\n- 项目二')],
    });

    const items = wrapper.findAll('.markdown li');
    expect(items).toHaveLength(2);
    expect(items[0].text()).toBe('项目一');
  });

  it('falls back to plaintext highlighting and escapes unknown code languages', () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '```foo\n<x & y>\n```')],
    });

    expect(wrapper.find('.code-lang').text()).toBe('plaintext');
    expect(wrapper.find('.code-block').html()).toContain('&lt;x &amp; y&gt;');
    expect(wrapper.find('.code-block').html()).not.toContain('<x & y>');
  });
});

// ── 渲染安全：消毒 / reverse-tabnabbing / 代码块 ───────────────

describe('markdown safety', () => {
  it('strips scripts, inline handlers and javascript: URLs from LLM output', () => {
    const wrapper = mountList({
      messages: [
        assistantMessage(
          'a1',
          '前言<script>alert(1)</script><img src="x" onerror="alert(1)">[点我](javascript:alert(2))',
        ),
      ],
    });

    const html = wrapper.find('.markdown').html();
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onerror');
    expect(html).not.toContain('javascript:');
  });

  it('keeps target="_blank" links but forces rel="noopener noreferrer"', () => {
    const wrapper = mountList({
      messages: [
        assistantMessage(
          'a1',
          '<a href="https://example.com" target="_blank">外部文档</a>',
        ),
      ],
    });

    const html = wrapper.find('.markdown').html();
    expect(html).toContain('target="_blank"');
    expect(html).toContain('noopener');
    expect(html).toContain('noreferrer');
  });

  it('renders fenced code with line numbers and a base64 copy button', async () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '示例：\n\n```js\nconst a = 1;\n```\n')],
    });

    const block = wrapper.find('.code-block');
    expect(block.exists()).toBe(true);
    expect(wrapper.find('.code-lang').text()).toBe('javascript');
    expect(wrapper.findAll('.code-line')).toHaveLength(1);

    const writeText = vi.fn().mockResolvedValue(undefined);
    stubClipboard(writeText);
    await wrapper.find('.copy-code-btn').trigger('click');
    await flushPromises();

    expect(writeText).toHaveBeenCalledWith('const a = 1;');
    expect(successMock).toHaveBeenCalledWith('代码已复制');
  });

  it('reports an error when copying code fails', async () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '```\nboom\n```')],
    });

    stubClipboard(vi.fn().mockRejectedValue(new Error('denied')));
    await wrapper.find('.copy-code-btn').trigger('click');
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('代码复制失败');
  });
});

// ── 引用：条款卡片 / 旧版 chips / 关键词高亮 ───────────────────

describe('citations', () => {
  /** 条款卡片夹具：done 态 + matchedQuestion（供关键词高亮断言）+ 单条引用卡片 */
  function messageWithCards(): ChatMessage {
    return {
      ...assistantMessage('a1', '依据如下'),
      state: 'done',
      matchedQuestion: '学费、住宿',
      citationCards: [
        {
          index: 1,
          file: 'student_handbook.pdf',
          page: 12,
          chunkIndex: 3,
          snippet: '学费说明',
        },
      ],
    };
  }

  it('renders citation cards with pretty file names and page numbers', () => {
    const wrapper = mountList({ messages: [messageWithCards()] });

    const head = wrapper.find('.citation-card-head');
    expect(head.text()).toContain('[1]');
    expect(head.text()).toContain('student handbook'); // 下划线转空格、去 .pdf 后缀
    expect(wrapper.find('.citation-card-page').text()).toContain('P12');
  });

  it('emits toggle-citation-card with the message-scoped key on head click', async () => {
    const wrapper = mountList({ messages: [messageWithCards()] });

    await wrapper.find('.citation-card-head').trigger('click');

    expect(wrapper.emitted('toggle-citation-card')![0]).toEqual(['a1-1']);
  });

  it('emits open-citation with the source locator from 展开原文', async () => {
    const wrapper = mountList({ messages: [messageWithCards()] });

    await wrapper.find('.citation-card-link').trigger('click');

    expect(wrapper.emitted('open-citation')![0]).toEqual([
      'source=student_handbook.pdf, chunk=3',
    ]);
  });

  it('highlights question keywords inside snippets with <mark>', () => {
    const wrapper = mountList({ messages: [messageWithCards()] });

    expect(wrapper.find('.citation-card-snippet').html()).toContain(
      '<mark>学费</mark>说明',
    );
  });

  it('escapes snippets and skips highlighting when no question is known', () => {
    const wrapper = mountList({
      messages: [
        {
          ...assistantMessage('a1', '正文'),
          citationCards: [
            { index: 1, file: 'r.pdf', page: null, chunkIndex: 0, snippet: '<b>学费</b>' },
          ],
        },
      ],
    });

    const html = wrapper.find('.citation-card-snippet').html();
    expect(html).not.toContain('<b>');
    expect(html).toContain('&lt;b&gt;');
    expect(html).not.toContain('<mark>');
  });

  it('renders legacy citation chips and emits open-citation with the raw citation', async () => {
    const wrapper = mountList({
      messages: [
        {
          ...assistantMessage('a1', '答案'),
          citations: ['学发〔2024〕1号', '教字〔2023〕9号'],
        },
      ],
    });

    const chips = wrapper.findAll('.citation-chip');
    expect(chips).toHaveLength(2);
    expect(chips[0].text()).toBe('[1] 学发〔2024〕1号');

    await chips[1].trigger('click');
    expect(wrapper.emitted('open-citation')![0]).toEqual(['教字〔2023〕9号']);
  });

  it('renders citation cards or legacy chips, never both', () => {
    const wrapper = mountList({
      messages: [
        {
          ...assistantMessage('a1', '答案'),
          citationCards: [
            { index: 1, file: 'r.pdf', page: null, chunkIndex: 0, snippet: '片段' },
          ],
          citations: ['学发〔2024〕1号'],
        },
      ],
    });

    expect(wrapper.find('.citation-card-list').exists()).toBe(true);
    expect(wrapper.find('.citation-list').exists()).toBe(false);
  });

  it('shows the fallback also for an empty citations array', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), citations: [] }],
    });

    expect(wrapper.find('.no-citation-fallback').exists()).toBe(true);
    expect(wrapper.find('.citation-list').exists()).toBe(false);
  });

  it('reflects expandedCitationCards in the caret direction and snippet class', () => {
    const message = {
      ...assistantMessage('a1', '答案'),
      citationCards: [
        { index: 1, file: 'r.pdf', page: null, chunkIndex: 0, snippet: '片段' },
      ],
    };

    const collapsed = mountList({ messages: [message] });
    expect(collapsed.find('.citation-card-caret').text()).toBe('▾');
    expect(collapsed.find('.citation-card-snippet').classes()).not.toContain('expanded');

    const expanded = mountList({
      messages: [message],
      expandedCitationCards: new Set(['a1-1']),
    });
    expect(expanded.find('.citation-card-caret').text()).toBe('▴');
    expect(expanded.find('.citation-card-snippet').classes()).toContain('expanded');
  });

  it('falls back to 规章制度 for blank or separator-only file names', () => {
    const wrapper = mountList({
      messages: [
        {
          ...assistantMessage('a1', '答案'),
          citationCards: [
            { index: 1, file: '', page: null, chunkIndex: 0, snippet: '片段一' },
            { index: 2, file: '-_-_.pdf', page: null, chunkIndex: 1, snippet: '片段二' },
          ],
        },
      ],
    });

    const heads = wrapper.findAll('.citation-card-file');
    expect(heads).toHaveLength(2);
    expect(heads[0].text()).toBe('规章制度');
    expect(heads[1].text()).toBe('规章制度');
  });

  it('dedupes question keywords and highlights every distinct run', () => {
    const wrapper = mountList({
      messages: [
        {
          ...assistantMessage('a1', '答案'),
          matchedQuestion: '学费 学费、住宿',
          citationCards: [
            { index: 1, file: 'r.pdf', page: null, chunkIndex: 0, snippet: '学费和住宿安排' },
          ],
        },
      ],
    });

    const html = wrapper.find('.citation-card-snippet').html();
    expect(html).toContain('<mark>学费</mark>和<mark>住宿</mark>安排');
  });

  it('shows the no-citation fallback only when nothing citable exists', () => {
    const bare = mountList({
      messages: [{ ...assistantMessage('a1', '常规回答'), state: 'done' }],
    });
    expect(bare.find('.no-citation-fallback').exists()).toBe(true);

    const selfIntro = mountList({
      messages: [{ ...assistantMessage('a2', '我是有章，可以回答规章问题'), state: 'done' }],
    });
    expect(selfIntro.find('.no-citation-fallback').exists()).toBe(false);

    const withCitations = mountList({
      messages: [
        { ...assistantMessage('a3', '答案'), state: 'done', citations: ['学发〔2024〕1号'] },
      ],
    });
    expect(withCitations.find('.no-citation-fallback').exists()).toBe(false);
  });
});

// ── 证据片段 ─────────────────────────────────────────────────

describe('evidence panel', () => {
  it('lists evidence snippets when provided', () => {
    const wrapper = mountList({
      messages: [
        { ...assistantMessage('a1', '答案'), evidence: ['片段一', '片段二'] },
      ],
    });

    const items = wrapper.findAll('.evidence-panel li');
    expect(items).toHaveLength(2);
    expect(items[0].text()).toBe('片段一');
    expect(wrapper.find('.evidence-panel .citation-title').text()).toBe('证据片段');
  });

  it('hides the panel when evidence is absent or empty', () => {
    const absent = mountList({ messages: [assistantMessage('a1', '答案')] });
    expect(absent.find('.evidence-panel').exists()).toBe(false);

    const empty = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), evidence: [] }],
    });
    expect(empty.find('.evidence-panel').exists()).toBe(false);
  });
});

// ── 动作按钮：复制 / 重试 / 评分 / 编辑 ────────────────────────

describe('message actions', () => {
  it('copies user message content to the clipboard on 复制', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    stubClipboard(writeText);
    const wrapper = mountList({ messages: [userMessage('u1', '要复制的内容')] });

    await buttonByText(wrapper, '复制').trigger('click');
    await flushPromises();

    expect(writeText).toHaveBeenCalledWith('要复制的内容');
    expect(successMock).toHaveBeenCalledWith('已复制');
  });

  it('reports an error when copying a message fails', async () => {
    stubClipboard(vi.fn().mockRejectedValue(new Error('denied')));
    const wrapper = mountList({ messages: [userMessage('u1', '内容')] });

    await buttonByText(wrapper, '复制').trigger('click');
    await flushPromises();

    expect(errorMock).toHaveBeenCalledWith('复制失败');
  });

  it('emits regenerate-from with the original message index', async () => {
    const wrapper = mountList({
      messages: [userMessage('u1', '问题'), assistantMessage('a1', '回答')],
    });

    await buttonByText(wrapper, '重试分支').trigger('click');

    expect(wrapper.emitted('regenerate-from')![0]).toEqual([1]);
  });

  it('emits rate-answer with 5 / 1 for the feedback buttons', async () => {
    const message = { ...assistantMessage('a1', '回答'), state: 'done' as const };
    const wrapper = mountList({ messages: [userMessage('u0', '问题'), message] });

    await buttonByText(wrapper, '👍有帮助').trigger('click');
    await buttonByText(wrapper, '👎待改进').trigger('click');

    expect(wrapper.emitted('rate-answer')![0]).toEqual([1, message, 5]);
    expect(wrapper.emitted('rate-answer')![1]).toEqual([1, message, 1]);
  });

  it('disables both rating buttons once feedback exists or is in flight', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '回答'), state: 'done' }],
      answerFeedbackMap: { a1: 5 },
      answerFeedbackLoading: { a1: true },
    });

    expect(buttonByText(wrapper, '👍有帮助').attributes('disabled')).toBeDefined();
    expect(buttonByText(wrapper, '👎待改进').attributes('disabled')).toBeDefined();
  });

  it('keeps rating buttons enabled when feedback state belongs to another message', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '回答'), state: 'done' }],
      answerFeedbackMap: { other: 5 },
      answerFeedbackLoading: { other: true },
    });

    expect(buttonByText(wrapper, '👍有帮助').attributes('disabled')).toBeUndefined();
    expect(buttonByText(wrapper, '👎待改进').attributes('disabled')).toBeUndefined();
  });

  it('emits start-edit with the message on 编辑后重发', async () => {
    const message = userMessage('u1', '旧问题');
    const wrapper = mountList({ messages: [message] });

    await buttonByText(wrapper, '编辑后重发').trigger('click');

    expect(wrapper.emitted('start-edit')![0]).toEqual([message]);
  });

  it('emits cancel-edit from the edit box', async () => {
    const wrapper = mountList({
      messages: [userMessage('u1', '旧问题')],
      editingMessageId: 'u1',
      editingMessageDraft: '旧问题',
    });

    await wrapper.find('.edit-actions button').trigger('click');

    expect(wrapper.emitted('cancel-edit')).toHaveLength(1);
  });

  it('binds the edit draft via v-model and submits with index and message id', async () => {
    const wrapper = mountList({
      messages: [userMessage('u1', '旧问题')],
      editingMessageId: 'u1',
      editingMessageDraft: '旧问题',
    });

    await wrapper.find('.edit-textarea').setValue('新问题');
    expect(wrapper.emitted('update:editingMessageDraft')![0]).toEqual(['新问题']);

    const submit = buttonByText(wrapper, '编辑后重发分支');
    expect(submit.attributes('disabled')).toBeUndefined();
    await submit.trigger('click');
    expect(wrapper.emitted('submit-edit')![0]).toEqual([0, 'u1']);
  });

  it('keeps the submit button disabled while the draft is blank', () => {
    const wrapper = mountList({
      messages: [userMessage('u1', '旧问题')],
      editingMessageId: 'u1',
      editingMessageDraft: '   ',
    });

    expect(buttonByText(wrapper, '编辑后重发分支').attributes('disabled')).toBeDefined();
  });
});

// ── 检索链路与 Agent 轨迹 ─────────────────────────────────────

describe('retrieval trace', () => {
  /** 检索链路夹具：degraded=true 用于断言降级提示，coarseMs+fineMs=75ms */
  const trace = {
    recall: 120,
    coarse: 40,
    fine: 10,
    coarseMs: 30,
    fineMs: 45,
    degraded: true,
    coarseReranker: 'bge-reranker-base',
    fineReranker: 'bge-reranker-v2',
  };

  it('summarizes recall/coarse/fine and total ms on the toggle row', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), state: 'done', retrievalTrace: trace }],
    });

    const text = wrapper.find('.retrieval-trace-toggle').text();
    expect(text).toContain('召回 120');
    expect(text).toContain('粗排 40');
    expect(text).toContain('精排 10');
    expect(text).toContain('75ms'); // coarseMs + fineMs
  });

  it('shows the trace table and degradation note when expanded', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), state: 'done', retrievalTrace: trace }],
      expandedTraceOpen: true,
    });

    const detail = wrapper.find('.retrieval-trace-detail');
    expect(detail.exists()).toBe(true);
    expect(detail.text()).toContain('bge-reranker-base');
    expect(detail.text()).toContain('bge-reranker-v2');
    expect(wrapper.find('.retrieval-trace-note').text()).toContain('已降级');
  });

  it('marks the detail table as placeholder when no trace was attached', () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), state: 'done' }],
      expandedTraceOpen: true,
    });

    expect(wrapper.find('.retrieval-trace-note').text()).toContain('轨迹未接入');
  });

  it('emits toggle-trace-open from the toggle button', async () => {
    const wrapper = mountList({
      messages: [{ ...assistantMessage('a1', '答案'), state: 'done' }],
    });

    await wrapper.find('.retrieval-trace-toggle').trigger('click');

    expect(wrapper.emitted('toggle-trace-open')).toHaveLength(1);
  });
});

describe('agent trace timeline', () => {
  /** 两步轨迹夹具：tool_call（带 actionInput）+ finish，覆盖步数/时长/最后步 class 断言 */
  const traceSteps: ReactTraceStep[] = [
    { step: 1, thought: '需要检索', action: 'tool_call', actionInput: { query: '学费' } },
    { step: 2, thought: '信息足够', action: 'finish' },
  ];

  it('shows the timeline on the last message with step count and duration', () => {
    const wrapper = mountList({
      messages: [assistantMessage('a1', '最终回答')],
      traceSteps,
      traceDurationMs: 4200,
    });

    const panel = wrapper.find('.trace-timeline-panel');
    expect(panel.exists()).toBe(true);
    expect(wrapper.find('.trace-timeline-meta').text()).toBe('2 步 · 4200ms');
    expect(wrapper.findAll('.trace-timeline-step')).toHaveLength(2);
    expect(panel.text()).toContain('💭 Thought');
    // actionInput 以 JSON.stringify(x, null, 2) 展示在 <pre> 中
    expect(panel.text()).toContain('"query"');
    expect(panel.text()).toContain('学费');
    expect(wrapper.findAll('.trace-timeline-step')[1].classes()).toContain('trace-last');
  });

  it('renders observations as raw text for strings and JSON for objects', () => {
    const steps: ReactTraceStep[] = [
      { step: 1, thought: '需要检索', action: 'tool_call', observation: '召回 120 条' },
      { step: 2, thought: '信息足够', action: 'finish', observation: { hits: 3 } },
    ];
    const wrapper = mountList({
      messages: [assistantMessage('a1', '最终回答')],
      traceSteps: steps,
      traceDurationMs: 4200,
    });

    const blocks = wrapper.findAll('.trace-obs-block');
    expect(blocks).toHaveLength(2);
    expect(blocks[0].text()).toContain('召回 120 条');
    expect(blocks[1].text()).toContain('"hits"');
    expect(blocks[1].text()).toContain('3');

    // 首步渲染静态的检索四路召回示意
    expect(wrapper.find('.trace-retrieval-lanes').exists()).toBe(true);
    expect(wrapper.find('.trace-retrieval-lanes').text()).toContain('Vector 40%');
  });

  it('does not render the timeline on earlier messages or without steps', () => {
    const withStepsButNotLast = mountList({
      messages: [assistantMessage('a1', '中间回答'), assistantMessage('a2', '最终回答')],
      traceSteps,
      traceDurationMs: 4200,
    });
    expect(withStepsButNotLast.findAll('.trace-timeline-panel')).toHaveLength(1);

    const withoutSteps = mountList({
      messages: [assistantMessage('a1', '最终回答')],
      traceDurationMs: 4200,
    });
    expect(withoutSteps.find('.trace-timeline-panel').exists()).toBe(false);
  });
});

// ── 对外 API：defineExpose ───────────────────────────────────

describe('exposed methods', () => {
  it('exposes scrollToBottom and resetHeights to the parent', () => {
    const wrapper = mountList({ messages: [assistantMessage('a1', '答案')] });

    expect(typeof (wrapper.vm as unknown as Record<string, unknown>).scrollToBottom).toBe(
      'function',
    );
    expect(typeof (wrapper.vm as unknown as Record<string, unknown>).resetHeights).toBe(
      'function',
    );
  });
});
