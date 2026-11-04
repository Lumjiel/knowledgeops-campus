<!--
  MessageList.vue — 聊天消息列表组件（自实现窗口虚拟滚动版）

  职责：
  - 渲染会话消息：用户/助手气泡、条款级引用卡片、来源引用 chips、证据片段、
    检索链路表、Agent 执行轨迹时间线、消息操作按钮（复制/重试/评分/编辑重发）；
  - 长会话性能：按消息行实测高度 + 上下占位符做窗口虚拟滚动，仅渲染可视区
    前后各 OVERSCAN_COUNT 条（估算行高 ESTIMATED_ROW_HEIGHT，测量后回填）；
  - 助手正文渲染：marked（GFM + breaks）→ highlight.js 代码高亮 → DOMPurify
    消毒（含 reverse-tabnabbing 防护），流式阶段先走纯文本避免逐 token 重渲染；
  - 展开态（引用卡片/检索链路）与编辑态等由父组件持有，本组件通过 emits 上抛
    交互事件，保证切到评测页后状态跨视图保留。

  约束：父组件原地变更 props.messages，本组件只负责渲染与行高测量，不直接修改消息数据。
-->
<template>
  <section
    ref="containerRef"
    class="messages"
    @scroll="onMessageScroll"
    @click="handleMarkdownClick"
  >
    <div v-if="hydrating" class="hydration-skeleton">
      <div class="skeleton-line lg"></div>
      <div class="skeleton-line"></div>
      <div class="skeleton-line short"></div>
      <div class="skeleton-bubble"></div>
      <div class="skeleton-bubble alt"></div>
    </div>

    <template v-else>
      <div v-if="isEmptyConversation" class="empty-hint">
        <p>从下面的示例问题开始，或直接输入你的问题</p>
      </div>

      <!-- 虚拟滚动上下占位符：高度 = 未渲染消息的累计行高，撑起真实滚动条 -->
      <div class="virtual-spacer" :style="{ height: `${virtualTopSpacer}px` }"></div>

      <!-- 消息行：只渲染虚拟窗口（virtualMessages）内的条目，
           data-msg-id 供 ResizeObserver 回写实测行高 -->
      <article
        v-for="entry in virtualMessages"
        :key="entry.item.id"
        :ref="(el) => setMessageRowRef(entry.item.id, el as HTMLElement | null)"
        :data-msg-id="entry.item.id"
        class="message-row"
        :class="[entry.item.role, entry.item.state || 'done']"
      >
        <div class="avatar">{{ entry.item.role === 'user' ? '我' : '章' }}</div>
        <div class="bubble-wrap">
          <div class="bubble-meta">
            <span>{{ entry.item.role === 'user' ? '你' : '有章' }}</span>
            <span>{{ formatTime(entry.item.createdAt) }}</span>
            <span v-if="entry.item.state === 'streaming'" class="status-dot">生成中</span>
            <span v-if="entry.item.state === 'pending'" class="status-dot">思考中</span>
          </div>

          <div class="bubble">
            <template v-if="entry.item.role === 'assistant'">
              <div
                v-if="entry.item.state === 'pending' && !entry.item.content"
                class="assistant-skeleton"
              >
                <div></div>
                <div></div>
                <div></div>
              </div>
              <div v-else>
                <p v-if="entry.item.state === 'streaming'" class="streaming-plain">
                  {{ entry.item.content }}
                </p>
                <!-- eslint-disable vue/no-v-html -- 内容经 DOMPurify 消毒 -->
                <div
                  v-else
                  class="markdown"
                  v-html="renderMarkdown(entry.item.content)"
                ></div>
                <!-- eslint-enable vue/no-v-html -->
                <div v-if="entry.item.citationCards?.length" class="citation-panel">
                  <p class="citation-title">条款依据</p>
                  <div class="citation-card-list">
                    <div
                      v-for="card in entry.item.citationCards"
                      :key="`${entry.item.id}-card-${card.index}`"
                      class="citation-card"
                    >
                      <button
                        type="button"
                        class="citation-card-head"
                        @click="$emit('toggle-citation-card', `${entry.item.id}-${card.index}`)"
                      >
                        <span class="citation-card-index">[{{ card.index }}]</span>
                        <span class="citation-card-file">{{
                          prettyFileName(card.file)
                        }}</span>
                        <span v-if="card.page != null" class="citation-card-page"
                          >· P{{ card.page }}</span
                        >
                        <span class="citation-card-caret">{{
                          expandedCitationCards.has(`${entry.item.id}-${card.index}`)
                            ? '▴'
                            : '▾'
                        }}</span>
                      </button>
                      <!-- eslint-disable vue/no-v-html -- 内容经 DOMPurify 消毒 -->
                      <p
                        class="citation-card-snippet"
                        :class="{
                          expanded: expandedCitationCards.has(
                            `${entry.item.id}-${card.index}`,
                          ),
                        }"
                        v-html="renderSnippet(card.snippet, entry.item.matchedQuestion)"
                      ></p>
                      <!-- eslint-enable vue/no-v-html -->
                      <div class="citation-card-actions">
                        <button
                          type="button"
                          class="citation-card-link"
                          @click="$emit('open-citation', citationTextOf(card))"
                        >
                          展开原文
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                <div v-else-if="entry.item.citations?.length" class="citation-panel">
                  <p class="citation-title">来源引用</p>
                  <div class="citation-list">
                    <button
                      v-for="(citation, citationIndex) in entry.item.citations"
                      :key="`${entry.item.id}-${citationIndex}`"
                      type="button"
                      class="citation-chip"
                      @click="$emit('open-citation', citation)"
                    >
                      [{{ citationIndex + 1 }}] {{ citation }}
                    </button>
                  </div>
                </div>
                <div
                  v-if="
                    entry.item.role === 'assistant' &&
                    !hasCitationSource(entry.item) &&
                    !entry.item.content.includes('我是有章')
                  "
                  class="no-citation-fallback"
                >
                  ⚠ 以上为通用参考信息，暂未匹配到可引用的规章原文，请以学校官方最新通知为准
                </div>
                <div
                  v-if="entry.item.role === 'assistant' && entry.item.state === 'done'"
                  class="retrieval-trace-row"
                >
                  <button
                    type="button"
                    class="retrieval-trace-toggle"
                    @click="$emit('toggle-trace-open')"
                  >
                    <template v-if="entry.item.retrievalTrace">
                      检索链路 · 召回 {{ entry.item.retrievalTrace.recall }} → 粗排
                      {{ entry.item.retrievalTrace.coarse }} → 精排
                      {{ entry.item.retrievalTrace.fine }} ·
                      {{
                        entry.item.retrievalTrace.coarseMs + entry.item.retrievalTrace.fineMs
                      }}ms ▾
                    </template>
                    <template v-else>检索链路 ▾</template>
                  </button>
                  <div v-if="expandedTraceOpen" class="retrieval-trace-detail">
                    <p v-if="!entry.item.retrievalTrace" class="retrieval-trace-note">
                      轨迹未接入：以下为占位行，非真实耗时
                    </p>
                    <table>
                      <thead>
                        <tr>
                          <th>阶段</th>
                          <th>输入</th>
                          <th>输出</th>
                          <th>耗时</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr>
                          <td>向量召回</td>
                          <td>—</td>
                          <td>{{ entry.item.retrievalTrace?.recall ?? '—' }}</td>
                          <td>—</td>
                        </tr>
                        <tr>
                          <td>
                            粗排（{{ entry.item.retrievalTrace?.coarseReranker ?? '—' }}）
                          </td>
                          <td>{{ entry.item.retrievalTrace?.recall ?? '—' }}</td>
                          <td>{{ entry.item.retrievalTrace?.coarse ?? '—' }}</td>
                          <td>
                            {{
                              entry.item.retrievalTrace
                                ? entry.item.retrievalTrace.coarseMs + 'ms'
                                : '—'
                            }}
                          </td>
                        </tr>
                        <tr>
                          <td>
                            精排（{{ entry.item.retrievalTrace?.fineReranker ?? '—' }}）
                          </td>
                          <td>{{ entry.item.retrievalTrace?.coarse ?? '—' }}</td>
                          <td>{{ entry.item.retrievalTrace?.fine ?? '—' }}</td>
                          <td>
                            {{
                              entry.item.retrievalTrace
                                ? entry.item.retrievalTrace.fineMs + 'ms'
                                : '—'
                            }}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                    <p
                      v-if="entry.item.retrievalTrace?.degraded"
                      class="retrieval-trace-note"
                    >
                      ⚠ 精排超时，已降级为粗排结果
                    </p>
                  </div>
                </div>
                <div v-if="entry.item.evidence?.length" class="evidence-panel">
                  <p class="citation-title">证据片段</p>
                  <ul>
                    <li
                      v-for="(snippet, snippetIndex) in entry.item.evidence"
                      :key="`${entry.item.id}-ev-${snippetIndex}`"
                    >
                      {{ snippet }}
                    </li>
                  </ul>
                </div>
                <!-- Agent Trace Timeline -->
                <div
                  v-if="traceSteps.length && entry.index === virtualMessages.length - 1"
                  class="trace-timeline-panel"
                >
                  <div class="trace-timeline-header">
                    <span class="trace-timeline-title">Agent 执行轨迹</span>
                    <span class="trace-timeline-meta"
                      >{{ traceSteps.length }} 步 · {{ traceDurationMs }}ms</span
                    >
                  </div>
                  <div class="trace-timeline">
                    <div
                      v-for="(ts, tsIdx) in traceSteps"
                      :key="`trace-${tsIdx}`"
                      class="trace-timeline-step"
                      :class="[
                        `trace-action-${ts.action}`,
                        { 'trace-last': tsIdx === traceSteps.length - 1 },
                      ]"
                    >
                      <div class="trace-timeline-rail">
                        <div class="trace-node"></div>
                        <div
                          v-if="tsIdx < traceSteps.length - 1"
                          class="trace-connector"
                        ></div>
                      </div>
                      <div class="trace-timeline-content">
                        <div class="trace-step-header">
                          <span class="trace-step-label">Step {{ ts.step }}</span>
                          <span class="trace-action-badge" :class="`badge-${ts.action}`">{{
                            ts.action
                          }}</span>
                        </div>
                        <div v-if="ts.thought" class="trace-thought-block">
                          <span class="trace-field-label">💭 Thought</span>
                          <p>{{ ts.thought }}</p>
                        </div>
                        <div
                          v-if="ts.actionInput && Object.keys(ts.actionInput).length"
                          class="trace-input-block"
                        >
                          <span class="trace-field-label">🔧 Input</span>
                          <pre>{{ JSON.stringify(ts.actionInput, null, 2) }}</pre>
                        </div>
                        <details v-if="ts.observation" class="trace-obs-block">
                          <summary>
                            <span class="trace-field-label">📋 Observation</span>
                          </summary>
                          <div class="trace-obs-content">
                            <pre>{{
                              typeof ts.observation === 'string'
                                ? ts.observation
                                : JSON.stringify(ts.observation, null, 2)
                            }}</pre>
                          </div>
                        </details>
                        <div v-if="tsIdx === 0" class="trace-retrieval-lanes">
                          <span class="trace-field-label">检索四路召回</span>
                          <div class="retrieval-bar">
                            <div class="retrieval-lane vector" style="width: 40%">
                              <span>Vector 40%</span>
                            </div>
                            <div class="retrieval-lane keyword" style="width: 25%">
                              <span>Keyword 25%</span>
                            </div>
                            <div class="retrieval-lane graph" style="width: 20%">
                              <span>Graph 20%</span>
                            </div>
                            <div class="retrieval-lane web" style="width: 15%">
                              <span>Web 15%</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <template v-else>
              <div v-if="editingMessageId === entry.item.id" class="edit-box">
                <el-input
                  v-model="editingDraft"
                  type="textarea"
                  :rows="3"
                  resize="none"
                />
                <div class="edit-actions">
                  <el-button size="small" @click="$emit('cancel-edit')">取消</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :disabled="!editingMessageDraft.trim() || sending"
                    @click="$emit('submit-edit', entry.index, entry.item.id)"
                  >
                    编辑后重发分支
                  </el-button>
                </div>
              </div>
              <p v-else class="plain">{{ entry.item.content }}</p>
            </template>
          </div>

          <div class="message-actions">
            <button type="button" @click="copyMessage(entry.item.content)">复制</button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              @click="$emit('regenerate-from', entry.index)"
            >
              重试分支
            </button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              :disabled="
                Boolean(answerFeedbackMap[entry.item.id]) ||
                Boolean(answerFeedbackLoading[entry.item.id])
              "
              @click="$emit('rate-answer', entry.index, entry.item, 5)"
            >
              👍有帮助
            </button>
            <button
              v-if="entry.item.role === 'assistant'"
              type="button"
              :disabled="
                Boolean(answerFeedbackMap[entry.item.id]) ||
                Boolean(answerFeedbackLoading[entry.item.id])
              "
              @click="$emit('rate-answer', entry.index, entry.item, 1)"
            >
              👎待改进
            </button>
            <button
              v-if="entry.item.role === 'user'"
              type="button"
              @click="$emit('start-edit', entry.item)"
            >
              编辑后重发
            </button>
          </div>
        </div>
      </article>

      <div class="virtual-spacer" :style="{ height: `${virtualBottomSpacer}px` }"></div>

      <!-- 流式/生成状态提示行（状态文案由父组件下发） -->
      <div v-if="sending && isStreamingResponse" class="thinking">
        {{ streamStatusLabel }} · {{ streamStatusDetail || '处理中...' }}
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
/**
 * 脚本分区顺序：
 * 1) Markdown 渲染基建（模块级一次性初始化：hljs 语言注册、marked renderer、
 *    DOMPurify 钩子）——与组件实例无关，仅执行一次；
 * 2) Props / Emits 契约（状态由父组件持有，本组件为受控展示组件）；
 * 3) 展示工具函数（时间格式化、引用文案、snippet 高亮、复制）；
 * 4) 虚拟滚动状态与测量（行高缓存、窗口区间、占位高度）；
 * 5) 生命周期（ResizeObserver / window resize 绑定与清理）。
 */
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bashLang from 'highlight.js/lib/languages/bash';
import javaLang from 'highlight.js/lib/languages/java';
import javascriptLang from 'highlight.js/lib/languages/javascript';
import jsonLang from 'highlight.js/lib/languages/json';
import markdownLang from 'highlight.js/lib/languages/markdown';
import pythonLang from 'highlight.js/lib/languages/python';
import sqlLang from 'highlight.js/lib/languages/sql';
import typescriptLang from 'highlight.js/lib/languages/typescript';
import xmlLang from 'highlight.js/lib/languages/xml';
import yamlLang from 'highlight.js/lib/languages/yaml';
import { ElMessage } from 'element-plus';
import { marked } from 'marked';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { ReactTraceStep } from '../types/react';
import type { ChatCitationCard, ChatMessage } from '../types/chat';

/** 单条消息的虚拟滚动度量：条目、在 props.messages 中的原始下标、累计纵向偏移与行高（px） */
interface MessageMetric {
  item: ChatMessage;
  index: number;
  offset: number;
  height: number;
}

/** 未测量消息行的初始估算高度（px），实测后由 messageHeights 覆盖 */
const ESTIMATED_ROW_HEIGHT = 156;
/** 虚拟窗口在可视区上下额外多渲染的消息条数，降低滚动时白屏概率 */
const OVERSCAN_COUNT = 8;

hljs.registerLanguage('bash', bashLang);
hljs.registerLanguage('java', javaLang);
hljs.registerLanguage('javascript', javascriptLang);
hljs.registerLanguage('json', jsonLang);
hljs.registerLanguage('markdown', markdownLang);
hljs.registerLanguage('python', pythonLang);
hljs.registerLanguage('sql', sqlLang);
hljs.registerLanguage('typescript', typescriptLang);
hljs.registerLanguage('xml', xmlLang);
hljs.registerLanguage('yaml', yamlLang);

/** HTML 特殊字符转义（& < > " '），用于代码块与 snippet 的防注入预处理 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** UTF-8 安全的 Base64 编码（先 TextEncoder 再 btoa，避免非 ASCII 乱码） */
function toBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

/** toBase64 的逆操作：解码代码复制按钮 data-code 属性中的原始代码 */
function fromBase64(value: string): string {
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/**
 * 自定义代码块渲染器（marked renderer）：按语言做 highlight.js 高亮（未注册语言
 * 退化为转义明文），输出带行号的 .code-line 结构，并在工具栏挂「复制代码」按钮——
 * 原始代码经 Base64 编码塞进 data-code 属性（escapeHtml 转义防属性逃逸），
 * 点击时由 handleMarkdownClick 事件代理解码复制。模块级初始化，仅执行一次。
 */
const renderer = new marked.Renderer();
renderer.code = ((token: { text: string; lang?: string }) => {
  const rawCode = token.text ?? '';
  const lang = token.lang?.trim().toLowerCase().split(/\s+/)[0] ?? 'plaintext';
  const language = hljs.getLanguage(lang) ? lang : 'plaintext';
  const highlighted =
    language === 'plaintext'
      ? escapeHtml(rawCode)
      : hljs.highlight(rawCode, { language, ignoreIllegals: true }).value;

  const lines = highlighted.split('\n');
  const numbered = lines
    .map((line, index) => {
      const content = line || '&nbsp;';
      return `<span class="code-line"><span class="line-no">${index + 1}</span><span class="line-content">${content}</span></span>`;
    })
    .join('');

  const payload = escapeHtml(toBase64(rawCode));

  return `<div class="code-block"><div class="code-toolbar"><span class="code-lang">${language}</span><button class="copy-code-btn" type="button" data-code="${payload}">复制代码</button></div><pre><code class="hljs language-${language}">${numbered}</code></pre></div>`;
}) as typeof renderer.code;

marked.use({
  gfm: true,
  breaks: true,
  renderer,
});

// Module-level hook: force rel="noopener noreferrer" on every link with
// target="_blank" rendered from LLM output. Without this, a prompt-injected
// response could open a new tab and the new tab's JS would be able to reach
// back to window.opener.location of the original page (reverse-tabnabbing).
// Registered once at module load; DOMPurify hooks are keyed by event name and
// replace prior registrations, so this is safe across re-renders.
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('target') === '_blank') {
    const existing = (node.getAttribute('rel') || '').toLowerCase();
    const merged = new Set(existing.split(/\s+/).filter(Boolean));
    merged.add('noopener');
    merged.add('noreferrer');
    node.setAttribute('rel', Array.from(merged).join(' '));
  }
});

/**
 * 助手正文渲染管线：marked 解析（GFM + breaks）→ DOMPurify 白名单消毒。
 * 空内容返回占位文案。消毒策略为收紧的属性/标签白名单（禁 style、内联事件、
 * iframe/form 等），配合模块级 DOMPurify 钩子补 rel=noopener noreferrer，
 * 防 LLM 输出中的提示注入与 reverse-tabnabbing（见下方原注释）。
 */
function renderMarkdown(content: string): string {
  if (!content?.trim()) {
    return '<p>等待模型输出...</p>';
  }
  const html = marked.parse(content) as string;
  // Defense-in-depth: explicitly forbid inline event handlers, javascript:
  // URLs, and target=_blank without rel=noopener. DOMPurify already strips
  // the dangerous forms (script, onerror, javascript:), but the defaults
  // leave target and a few other attributes alone, which is enough to
  // enable reverse-tabnabbing on links rendered from LLM output.
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['data-code'],
    ALLOWED_ATTR: [
      'href',
      'title',
      'alt',
      'src',
      'name',
      'target',
      'rel',
      'class',
      'id',
      'data-code',
      'data-line',
      'colspan',
      'rowspan',
      'align',
    ],
    FORBID_ATTR: ['style', 'onload', 'onclick', 'onerror', 'onmouseover'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input'],
  });
}

/**
 * Props 契约（受控组件）：
 * - messages / hydrating / isEmptyConversation / sending / isStreamingResponse /
 *   streamStatusLabel / streamStatusDetail：会话数据与流式状态，由父组件下发；
 * - traceSteps / traceDurationMs：Agent 执行轨迹，仅展示；
 * - expandedCitationCards / expandedTraceOpen / editingMessageId /
 *   editingMessageDraft / answerFeedbackMap / answerFeedbackLoading：交互展开态，
 *   父组件持有以跨视图保留（切评测页卸载本组件不丢状态）。
 */
const props = defineProps<{
  /** 完整消息数组（由父组件持有并原地变更，组件仅负责渲染与测量） */
  messages: ChatMessage[];
  hydrating: boolean;
  isEmptyConversation: boolean;
  sending: boolean;
  isStreamingResponse: boolean;
  streamStatusLabel: string;
  streamStatusDetail: string;
  traceSteps: ReactTraceStep[];
  traceDurationMs: number;
  /** 展开态与编辑态由父组件持有：切换到评测页会卸载本组件，状态需跨视图保留 */
  expandedCitationCards: Set<string>;
  expandedTraceOpen: boolean;
  editingMessageId: string | null;
  editingMessageDraft: string;
  answerFeedbackMap: Record<string, number>;
  answerFeedbackLoading: Record<string, boolean>;
}>();

/**
 * 事件契约（本组件只上抛，不处理业务）：编辑草稿 v-model 同步、引用卡片/检索
 * 链路展开切换、打开引用原文、用户消息编辑（开始/取消/提交）、助手消息重试、
 * 点赞/点踩评分。参数语义见模板中的各触发点。
 */
const emit = defineEmits<{
  (e: 'update:editingMessageDraft', value: string): void;
  (e: 'toggle-citation-card', key: string): void;
  (e: 'toggle-trace-open'): void;
  (e: 'open-citation', citation: string): void;
  (e: 'start-edit', message: ChatMessage): void;
  (e: 'cancel-edit'): void;
  (e: 'submit-edit', messageIndex: number, messageId: string): void;
  (e: 'regenerate-from', assistantIndex: number): void;
  (e: 'rate-answer', index: number, message: ChatMessage, rating: number): void;
}>();

/** 编辑草稿的 v-model 桥接：读父组件 editingMessageDraft，写回 update 事件 */
const editingDraft = computed({
  get: () => props.editingMessageDraft,
  set: (value: string) => {
    emit('update:editingMessageDraft', value);
  },
});

/** 毫秒时间戳 → zh-CN 的 HH:mm 短时间文案（气泡 meta 行用） */
function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** 引用卡片 → 「展开原文」所需的定位文案（source 文件名 + chunk 下标） */
function citationTextOf(card: ChatCitationCard): string {
  return `source=${card.file}.pdf, chunk=${card.chunkIndex}`;
}

/** 消息是否携带任一形式的引用来源（条款卡片或来源 chips），决定是否显示无依据兜底条 */
function hasCitationSource(item: ChatMessage): boolean {
  return Boolean(item.citationCards?.length || item.citations?.length);
}

/** 从用户问题中提取 ≥2 字的中文词，用于 snippet 高亮；无匹配返回原文 */
function questionKeywords(question: string | undefined): string[] {
  if (!question) {
    return [];
  }
  const matches = question.match(/[\u4e00-\u9fa5]{2,}/g) ?? [];
  return [...new Set(matches)].filter((word) => word.length >= 2);
}

/** 文件名美化：去 .pdf 后缀、下划线/连字符转空格；空值回退「规章制度」 */
function prettyFileName(raw: string | undefined): string {
  if (!raw) return '规章制度';
  return (
    raw
      .replace(/\.pdf$/i, '')
      .replace(/[_-]+/g, ' ')
      .trim() || '规章制度'
  );
}

/**
 * snippet 渲染：先 HTML 转义再高亮（防注入），expanded 时展示全文。
 * 高亮只包 <mark>，匹配不到就原样返回，不报错。
 */
function renderSnippet(snippet: string, question?: string): string {
  const text = String(snippet ?? '');
  const safe = escapeHtml(text);
  const keywords = questionKeywords(question);
  if (!keywords.length) {
    return safe;
  }
  const pattern = new RegExp(
    `(${keywords.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`,
    'g',
  );
  return safe.replace(pattern, '<mark>$1</mark>');
}

/** 复制整条消息内容到剪贴板，成功/失败以 ElMessage 提示 */
async function copyMessage(content: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(content);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败');
  }
}

// ── 虚拟滚动状态与测量 ──────────────────────────────
// 行高缓存按消息 id 索引（id 稳定于会话/分支切换），viewportHeight/scrollTop
// 跟随滚动与窗口 resize 更新；messageRowElements 持有当前渲染行的 DOM 引用，
// 由 ResizeObserver 持续回写实测高度。
const messageHeights = ref<Record<string, number>>({});
const viewportHeight = ref(0);
const scrollTop = ref(0);
const containerRef = ref<HTMLElement | null>(null);
const messageRowElements = new Map<string, HTMLElement>();
let resizeObserver: ResizeObserver | null = null;

/** 全量消息的偏移量表：未实测的行用 ESTIMATED_ROW_HEIGHT，累计得到每条的 offset */
const messageMetrics = computed<MessageMetric[]>(() => {
  let offset = 0;
  return props.messages.map((item, index) => {
    const height = messageHeights.value[item.id] ?? ESTIMATED_ROW_HEIGHT;
    const metric = {
      item,
      index,
      offset,
      height,
    };
    offset += height;
    return metric;
  });
});

/** 虚拟列表总高度（最后一条的 offset + height），供底部占位计算 */
const totalVirtualHeight = computed(() => {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }
  const last = metrics[metrics.length - 1];
  return last.offset + last.height;
});

/**
 * 二分查找第一条「底边 ≥ targetOffset」的消息下标（offset 单调递增，O(log n)），
 * 空列表返回 0。
 */
function findMetricIndexByOffset(targetOffset: number): number {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }

  let left = 0;
  let right = metrics.length - 1;
  let answer = metrics.length - 1;

  while (left <= right) {
    const mid = (left + right) >> 1;
    const metric = metrics[mid];
    if (metric.offset + metric.height >= targetOffset) {
      answer = mid;
      right = mid - 1;
    } else {
      left = mid + 1;
    }
  }

  return answer;
}

/**
 * 当前虚拟窗口：可视区向上扩 0.8 屏、向下扩 1.8 屏定位起止下标，再各让出
 * OVERSCAN_COUNT 条缓冲；空会话返回 { start: 0, end: -1 } 表示无条目。
 */
const virtualRange = computed(() => {
  const total = props.messages.length;
  if (total === 0) {
    return { start: 0, end: -1 };
  }

  const startAnchor = Math.max(0, scrollTop.value - viewportHeight.value * 0.8);
  const endAnchor = scrollTop.value + viewportHeight.value * 1.8;

  const start = Math.max(0, findMetricIndexByOffset(startAnchor) - OVERSCAN_COUNT);
  const end = Math.min(total - 1, findMetricIndexByOffset(endAnchor) + OVERSCAN_COUNT);

  return { start, end };
});

/** 虚拟窗口内的消息切片（模板 v-for 的数据源），窗口无效时返回空数组 */
const virtualMessages = computed(() => {
  const metrics = messageMetrics.value;
  const { start, end } = virtualRange.value;
  if (end < start) {
    return [];
  }
  return metrics.slice(start, end + 1);
});

/** 顶部占位高度 = 窗口首条消息之前的累计偏移 */
const virtualTopSpacer = computed(() => virtualMessages.value[0]?.offset ?? 0);

/** 底部占位高度 = 总高度 − 窗口末条底边，撑起未渲染的尾部空间 */
const virtualBottomSpacer = computed(() => {
  if (virtualMessages.value.length === 0) {
    return 0;
  }

  const last = virtualMessages.value[virtualMessages.value.length - 1];
  return Math.max(0, totalVirtualHeight.value - (last.offset + last.height));
});

/** 同步可视区高度（挂载后与 window resize 时调用） */
function updateViewport(): void {
  viewportHeight.value = containerRef.value?.clientHeight ?? 0;
}

/** 滚动事件 → 回写 scrollTop，驱动 virtualRange 重算（高频 path，只做赋值） */
function onMessageScroll(): void {
  const element = containerRef.value;
  if (!element) {
    return;
  }
  scrollTop.value = element.scrollTop;
}

/**
 * 回写消息实测行高：非法值（≤0）忽略，与缓存差 ≤1px 时跳过（防 ResizeObserver
 * 抖动引发循环重渲染），变化有效则以新对象替换触发依赖更新。
 */
function syncMessageHeight(messageId: string, height: number): void {
  if (height <= 0) {
    return;
  }

  const current = messageHeights.value[messageId] ?? 0;
  if (Math.abs(current - height) <= 1) {
    return;
  }

  messageHeights.value = {
    ...messageHeights.value,
    [messageId]: height,
  };
}

/**
 * 模板 ref 回调：登记/注销消息行 DOM。首次挂载立即测量一次行高并纳入
 * ResizeObserver 观察；元素卸载（element 为 null）或被替换时解除旧观察。
 */
function setMessageRowRef(messageId: string, element: HTMLElement | null): void {
  const previous = messageRowElements.get(messageId);
  if (previous && previous !== element && resizeObserver) {
    resizeObserver.unobserve(previous);
    messageRowElements.delete(messageId);
  }

  if (!element) {
    return;
  }

  messageRowElements.set(messageId, element);
  syncMessageHeight(messageId, Math.ceil(element.getBoundingClientRect().height));
  if (resizeObserver) {
    resizeObserver.observe(element);
  }
}

/**
 * 滚动到底部（父组件经 defineExpose 调用）：等 nextTick DOM 更新后，
 * 仅在 force、距底不足 180px、或父组件正在发送（流式跟随）时执行，
 * 避免用户上翻阅读历史时被强行拉回。
 */
async function scrollToBottom(force = false): Promise<void> {
  await nextTick();
  const element = containerRef.value;
  if (!element) {
    return;
  }

  const remaining = element.scrollHeight - element.scrollTop - element.clientHeight;
  if (force || remaining < 180 || props.sending) {
    element.scrollTop = element.scrollHeight;
    scrollTop.value = element.scrollTop;
  }
}

/** 会话/分支切换后丢弃旧测量值，按估算行高重新布局（与原 loadSession 行为一致） */
function resetHeights(): void {
  messageHeights.value = {};
}

/**
 * 容器级点击代理：识别「复制代码」按钮（.copy-code-btn），解码 data-code 中的
 * Base64 原始代码并写入剪贴板。用事件代理是因为按钮由 v-html 注入，
 * 无法直接绑定 Vue 事件。
 */
async function handleMarkdownClick(event: MouseEvent): Promise<void> {
  const target = event.target as HTMLElement | null;
  const button = target?.closest('.copy-code-btn') as HTMLElement | null;
  if (!button) {
    return;
  }

  const payload = button.getAttribute('data-code');
  if (!payload) {
    return;
  }

  try {
    const raw = fromBase64(payload);
    await navigator.clipboard.writeText(raw);
    ElMessage.success('代码已复制');
  } catch {
    ElMessage.error('代码复制失败');
  }
}

/** 供父组件（App.vue）调用的两个命令式入口：跟随滚动、会话切换后重置行高 */
defineExpose({
  scrollToBottom,
  resetHeights,
});

/** 消息数组变更（浅监听）：剔除已不存在消息的行高缓存，避免陈旧测量参与布局 */
watch(
  () => props.messages,
  () => {
    const idSet = new Set(props.messages.map((message) => message.id));
    const filtered: Record<string, number> = {};
    Object.entries(messageHeights.value).forEach(([id, height]) => {
      if (idSet.has(id)) {
        filtered[id] = height;
      }
    });
    messageHeights.value = filtered;
    void scrollToBottom();
  },
  { deep: false },
);

/** 挂载：测视口、建 ResizeObserver（行高变化回写缓存）、监听窗口 resize */
onMounted(() => {
  updateViewport();

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver((entries) => {
      entries.forEach((entry) => {
        const element = entry.target as HTMLElement;
        const messageId = element.dataset.msgId;
        if (!messageId) {
          return;
        }
        syncMessageHeight(messageId, Math.ceil(entry.contentRect.height));
      });
    });
  }

  window.addEventListener('resize', updateViewport);
});

/** 卸载：解绑 resize 监听，解除所有行观察并销毁 ResizeObserver */
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport);

  if (resizeObserver) {
    messageRowElements.forEach((element) => {
      resizeObserver?.unobserve(element);
    });
    resizeObserver.disconnect();
    resizeObserver = null;
  }
});
</script>

<style scoped>
/* ════════════════════════════════════════════════
   聊天消息列表样式：自 App.vue 原样迁移，保持原有声明与顺序
   （设计令牌 --ui-* / --color-* 仍由 App.vue 的 .app-shell 提供）
   ════════════════════════════════════════════════ */

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 14px 0;
}

.hydration-skeleton {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px;
  display: grid;
  gap: 10px;
}

.skeleton-line,
.skeleton-bubble {
  border-radius: 10px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.18),
    rgba(148, 163, 184, 0.34),
    rgba(148, 163, 184, 0.18)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.skeleton-line {
  height: 12px;
}

.skeleton-line.lg {
  width: 52%;
}

.skeleton-line.short {
  width: 36%;
}

.skeleton-bubble {
  height: 84px;
}

.skeleton-bubble.alt {
  width: 76%;
  justify-self: end;
}

.message-actions button {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  cursor: pointer;
}

.virtual-spacer {
  width: 100%;
  pointer-events: none;
}

.message-row {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 18px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 12px;
  align-items: flex-start;
}

.message-row.user {
  grid-template-columns: minmax(0, 1fr) 38px;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  background: linear-gradient(150deg, #0f766e, #0369a1);
  color: #f8fafc;
}

.message-row.user .avatar {
  grid-column: 2;
  background: linear-gradient(150deg, #1d4ed8, #4338ca);
}

.bubble-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-row.user .bubble-wrap {
  align-items: flex-end;
}

.bubble-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--ui-muted);
  font-size: 12px;
}

.status-dot {
  color: var(--ui-accent);
}

.bubble {
  width: min(100%, 860px);
  border-radius: 14px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 86%, transparent);
  padding: 12px 14px;
}

.message-row.assistant .bubble {
  border: none;
  background: transparent;
  padding: 2px 0;
}

.message-row.user .bubble {
  background: linear-gradient(150deg, rgba(29, 78, 216, 0.15), rgba(67, 56, 202, 0.12));
}

.assistant-skeleton {
  display: grid;
  gap: 8px;
}

.assistant-skeleton div {
  height: 12px;
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.16),
    rgba(148, 163, 184, 0.3),
    rgba(148, 163, 184, 0.16)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.assistant-skeleton div:nth-child(3) {
  width: 65%;
}

.edit-box {
  display: grid;
  gap: 8px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.plain {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.message-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.citation-panel,
.evidence-panel {
  margin-top: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px 10px;
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
}

.citation-title {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ui-muted);
}

.citation-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.citation-chip {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
}

/* ── 条款级引用卡片（spec-A4/A9） ── */
.citation-card-list {
  display: grid;
  gap: 8px;
}

.citation-card {
  background: var(--color-quote-bg);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-chip);
  padding: 12px 16px;
  box-shadow: var(--shadow-card);
}

.citation-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  background: transparent;
  border: none;
  padding: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
}

.citation-card-index {
  font-variant-numeric: tabular-nums;
  color: var(--color-primary);
}

.citation-card-page {
  color: var(--color-text-sub);
  font-weight: 400;
}

.citation-card-caret {
  margin-left: auto;
  color: var(--color-text-sub);
}

.citation-card-snippet {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.citation-card-snippet.expanded {
  display: block;
  overflow: visible;
}

.citation-card-snippet mark {
  background: #fef08a;
  color: inherit;
  padding: 0 2px;
  border-radius: 4px;
}

.citation-card-actions {
  margin-top: 8px;
  text-align: right;
}

.citation-card-link {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-accent);
  cursor: pointer;
}

.citation-card-link:hover {
  text-decoration: underline;
}

/* ── 兜底提示条（spec-A7：黄底，不用红） ── */
.no-citation-fallback {
  margin-top: 8px;
  background: #fffbeb;
  border-left: 3px solid #fde68a;
  color: #92400e;
  border-radius: var(--radius-chip);
  padding: 8px 16px;
  font-size: 13px;
  line-height: 1.6;
}

/* ── 检索轨迹行（spec-A5：壳，标「轨迹未接入」） ── */
.retrieval-trace-row {
  margin-top: 8px;
}

.retrieval-trace-toggle {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 13px;
  color: var(--color-text-sub);
  cursor: pointer;
  font-variant-numeric: tabular-nums;
}

.retrieval-trace-detail {
  margin-top: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-chip);
  background: var(--color-surface);
  padding: 8px 16px;
}

.retrieval-trace-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-sub);
}

.retrieval-trace-detail table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.retrieval-trace-detail th,
.retrieval-trace-detail td {
  border-bottom: 1px solid var(--color-border);
  padding: 4px 8px;
  text-align: left;
  font-variant-numeric: tabular-nums;
}

.retrieval-trace-detail th {
  color: var(--color-text-sub);
  font-weight: 600;
}

.evidence-panel ul {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 4px;
  color: var(--ui-muted);
  font-size: 12px;
}

/* ── Agent Trace Timeline ─────────────────────────── */
.trace-timeline-panel {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-panel) 92%, transparent);
  border: 1px solid var(--ui-border);
}
.trace-timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.trace-timeline-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ui-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.trace-timeline-meta {
  font-size: 11px;
  color: var(--ui-muted);
}
.trace-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.trace-timeline-step {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  min-height: 48px;
}
.trace-timeline-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}
.trace-node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #6366f1;
  border: 2px solid var(--ui-card);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.25);
  flex-shrink: 0;
  z-index: 1;
}
.trace-action-tool_call .trace-node {
  background: #8b5cf6;
  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.25);
}
.trace-action-finish .trace-node {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.25);
}
.trace-action-error .trace-node {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.25);
}
.trace-connector {
  width: 2px;
  flex: 1;
  background: linear-gradient(180deg, rgba(99, 102, 241, 0.4), rgba(99, 102, 241, 0.08));
  min-height: 20px;
}
.trace-timeline-content {
  padding-bottom: 14px;
}
.trace-last .trace-timeline-content {
  padding-bottom: 0;
}
.trace-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.trace-step-label {
  font-weight: 700;
  font-size: 12px;
  color: var(--ui-text);
}
.trace-action-badge {
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #fff;
  background: #6366f1;
}
.badge-thought {
  background: #3b82f6;
}
.badge-tool_call {
  background: #8b5cf6;
}
.badge-finish {
  background: #10b981;
}
.badge-error {
  background: #f59e0b;
}
.trace-field-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ui-muted);
  margin-bottom: 3px;
  display: block;
}
.trace-thought-block p {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-text);
}
.trace-input-block pre,
.trace-obs-block pre {
  margin: 4px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 90%, transparent);
  font-size: 11px;
  overflow-x: auto;
  max-height: 100px;
  border: 1px solid var(--ui-border);
}
.trace-obs-block summary {
  cursor: pointer;
  padding: 2px 0;
}
.trace-retrieval-lanes {
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 80%, transparent);
  border: 1px solid var(--ui-border);
}
.retrieval-bar {
  display: flex;
  border-radius: 6px;
  overflow: hidden;
  margin-top: 6px;
  height: 24px;
  font-size: 10px;
  font-weight: 700;
}
.retrieval-lane {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  padding: 0 4px;
  transition: filter 0.2s ease;
}
.retrieval-lane:hover {
  filter: brightness(1.15);
}
.retrieval-lane.vector {
  background: #6366f1;
}
.retrieval-lane.keyword {
  background: #0ea5e9;
}
.retrieval-lane.graph {
  background: #f59e0b;
}
.retrieval-lane.web {
  background: #10b981;
}

.thinking {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 10px 70px;
  color: var(--ui-muted);
  font-size: 13px;
}

.markdown :deep(p) {
  margin: 0 0 10px;
  line-height: 1.7;
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 10px;
  padding-left: 20px;
}

.markdown :deep(code:not(.hljs)) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  background: rgba(15, 23, 42, 0.1);
  border-radius: 4px;
  padding: 2px 5px;
}

.markdown :deep(.code-block) {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  overflow: hidden;
  background: #0b1220;
  color: #e6edf7;
}

.markdown :deep(.code-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: #111b2f;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.markdown :deep(.code-lang) {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #9fb2cf;
}

.markdown :deep(.copy-code-btn) {
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 999px;
  padding: 4px 8px;
  font-size: 11px;
  background: rgba(15, 23, 42, 0.5);
  color: #e6edf7;
  cursor: pointer;
}

.markdown :deep(pre) {
  margin: 0;
  padding: 0;
  overflow-x: auto;
}

.markdown :deep(code.hljs) {
  display: block;
  padding: 10px 0;
  background: transparent;
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}

.markdown :deep(.code-line) {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 10px;
  padding: 0 12px;
  white-space: pre;
}

.markdown :deep(.line-no) {
  user-select: none;
  color: rgba(148, 163, 184, 0.7);
  text-align: right;
}

.markdown :deep(.line-content) {
  min-width: 0;
}

.markdown :deep(.hljs-comment),
.markdown :deep(.hljs-quote) {
  color: #8aa0bf;
}

.markdown :deep(.hljs-keyword),
.markdown :deep(.hljs-selector-tag),
.markdown :deep(.hljs-subst) {
  color: #ff8fa3;
}

.markdown :deep(.hljs-string),
.markdown :deep(.hljs-doctag) {
  color: #8be9a8;
}

.markdown :deep(.hljs-title),
.markdown :deep(.hljs-section),
.markdown :deep(.hljs-selector-id) {
  color: #8fc7ff;
}

.markdown :deep(.hljs-number),
.markdown :deep(.hljs-literal) {
  color: #f6c177;
}

@keyframes shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -120% 0;
  }
}

@media (max-width: 680px) {
  .message-row,
  .thinking,
  .hydration-skeleton {
    padding-left: 12px;
    padding-right: 12px;
  }

  .message-row {
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 8px;
  }

  .message-row.user {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .avatar {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }

  .thinking {
    padding-left: 52px;
  }
}

/* 流式中的纯文本渲染（完成后才转 markdown，避免逐 token 全量重渲染） */
.streaming-plain {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ── 空会话提示（极简） ── */
.empty-hint {
  margin: 14vh auto 0;
  max-width: 930px;
  padding: 0 20px;
  text-align: center;
}

.empty-hint p {
  margin: 0;
  font-size: 12.5px;
  color: var(--ui-muted);
  opacity: 0.75;
}

/* ── 消息 ── */
.avatar {
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
  border: 1px solid var(--ui-accent-line);
  font-size: 12px;
  font-weight: 500;
}

.message-row.user .avatar {
  background: var(--ui-panel);
  color: var(--ui-muted);
  border-color: var(--ui-border-soft);
}

.bubble {
  border: 1px solid var(--ui-border-soft);
  border-radius: 14px;
  box-shadow: var(--ui-shadow);
  background: var(--ui-surface);
  padding: 12px 16px;
  font-size: 13.5px;
  line-height: 1.7;
}

.message-row.user .bubble {
  background: var(--ui-accent);
  border-color: var(--ui-accent);
  color: #fff;
  border-radius: 14px 14px 5px 14px;
  width: fit-content;
  max-width: 100%;
  margin-left: auto;
  text-align: left;
}

.bubble-meta {
  font-size: 11px;
  color: var(--ui-muted);
}

.message-actions button {
  border: none;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 7px;
}

.message-actions button:hover:not(:disabled) {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

/* 引用依据卡片：淡纸色 */
.citation-title {
  font-weight: 600;
  font-size: 12.5px;
  color: var(--ui-text);
}

.citation-card {
  background: color-mix(in oklab, var(--ui-panel) 70%, transparent);
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  padding: 12px 16px;
  box-shadow: none;
}

.citation-card-file {
  font-weight: 600;
}

/* 检索链路行 */
.retrieval-trace-row {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ui-border-soft);
}

.retrieval-trace-toggle {
  border: none;
  background: none;
  color: var(--ui-muted);
  font-size: 11.5px;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}

.retrieval-trace-toggle:hover {
  color: var(--ui-accent);
}

/* 无依据兜底 */
.no-citation-fallback {
  border: 1px solid color-mix(in oklab, var(--ui-danger) 30%, transparent);
  background: color-mix(in oklab, var(--ui-danger) 6%, transparent);
  border-radius: 12px;
  font-size: 12.5px;
}

/* ════════ 响应式（原 App.vue @media 900px 中消息区部分） ════════ */
@media (max-width: 900px) {
  /* 消息区：卡片自适应铺满 */
  .message-row,
  .thinking,
  .hydration-skeleton,
  .empty-hint {
    max-width: 100%;
    padding: 0 12px 14px;
  }

  .bubble {
    padding: 11px 13px;
    border-radius: 12px;
  }

  .message-row.user .bubble {
    border-radius: 12px 12px 4px 12px;
  }
}
</style>
