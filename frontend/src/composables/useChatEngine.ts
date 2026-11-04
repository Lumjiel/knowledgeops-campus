/**
 * 对话引擎：SSE / JSON 问答、流式状态机、消息编辑重发、重新生成、
 * 回答评分反馈、引用展开与跳转。从 App.vue 抽出。
 * 会话镜像状态来自 useSessionStore，鉴权来自 useAuth，滚动/重置由 App 注入。
 */
import { ElMessage } from 'element-plus';
import { computed, onBeforeUnmount, ref } from 'vue';
import { reactChat, streamReactChat, submitAnswerFeedback } from '../api/client';
import type { ChatCitationCard, ChatMessage, RetrievalTrace } from '../types/chat';
import type {
  ReactChatResponse,
  ReactErrorEvent,
  ReactTokenEvent,
  ReactTraceStep,
} from '../types/react';
import { createMessage, deriveTitle } from '../utils/session';
import { errorStatus, toUserMessage } from '../utils/errors';
import { isGuestForbidden, reportGuestRestriction } from './useGuestRestriction';
import type { AuthStore } from './useAuth';
import type { SessionStore } from './useSessionStore';

type StreamPhase = 'idle' | 'thinking' | 'tool' | 'streaming' | 'done' | 'error' | 'stopped';

interface UseChatEngineOptions {
  persistState: () => void;
  auth: AuthStore;
  store: SessionStore;
  ui: {
    scrollToBottom: (force?: boolean) => Promise<void> | void;
  };
}

export function useChatEngine(options: UseChatEngineOptions) {
  const { persistState, auth, store, ui } = options;
  const {
    messages,
    traceSteps,
    chatId,
    modelProfile,
    streaming,
    prompt,
    editingMessageId,
    editingMessageDraft,
    activeSession,
    activeBranch,
    syncCurrentSessionBranch,
    forkBranch,
    loadSession,
  } = store;

  const sending = ref(false);
  const isStreamingResponse = ref(false);
  const streamPhase = ref<StreamPhase>('idle');
  const streamStatusDetail = ref('');
  const currentAbortController = ref<AbortController | null>(null);
  const retriedAuth = ref(false);

  const expandedCitationCards = ref<Set<string>>(new Set());
  const expandedTraceOpen = ref(false);

  const answerFeedbackMap = ref<Record<string, number>>({});
  const answerFeedbackLoading = ref<Record<string, boolean>>({});

  let streamResetTimer: number | null = null;

  const isEmptyConversation = computed(() => {
    const nonSystem = messages.value.filter((item) => item.role === 'user');
    return nonSystem.length === 0;
  });

  const streamStatusLabel = computed(() => {
    switch (streamPhase.value) {
      case 'thinking':
        return '思考中';
      case 'tool':
        return '工具调用中';
      case 'streaming':
        return '输出中';
      case 'done':
        return '已完成';
      case 'error':
        return '失败';
      case 'stopped':
        return '已停止';
      default:
        return '空闲';
    }
  });

  const streamStatusTagType = computed(() => {
    switch (streamPhase.value) {
      case 'thinking':
        return 'warning';
      case 'tool':
        return 'success';
      case 'streaming':
        return 'primary';
      case 'done':
        return 'success';
      case 'error':
        return 'danger';
      case 'stopped':
        return 'info';
      default:
        return 'info';
    }
  });

  const traceDurationMs = computed(() => {
    if (!traceSteps.value.length) return 0;
    // Estimate ~2s per step as rough timing if not available
    return traceSteps.value.length * 2000;
  });

  function scheduleStreamReset(): void {
    if (streamResetTimer) {
      window.clearTimeout(streamResetTimer);
    }
    streamResetTimer = window.setTimeout(() => {
      streamPhase.value = 'idle';
      streamStatusDetail.value = '';
      streamResetTimer = null;
    }, 1800);
  }

  function upsertTrace(step: ReactTraceStep): void {
    const index = traceSteps.value.findIndex((item) => item.step === step.step);
    if (index >= 0) {
      traceSteps.value[index] = step;
    } else {
      traceSteps.value.push(step);
      traceSteps.value.sort((a, b) => a.step - b.step);
    }
  }

  function toggleCitationCard(key: string): void {
    const next = new Set(expandedCitationCards.value);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    expandedCitationCards.value = next;
  }

  function toggleTraceOpen(): void {
    expandedTraceOpen.value = !expandedTraceOpen.value;
  }

  function parseCitation(citation: string): { source: string; chunk: string } | null {
    const matched = citation.match(/source=([^,]+),\s*chunk=(.+)$/i);
    if (!matched) {
      return null;
    }
    return {
      source: matched[1].trim(),
      chunk: matched[2].trim(),
    };
  }

  function openCitation(citation: string): void {
    const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
    const target = parseCitation(citation);
    const url = `${base}/ai/pdf/file/${encodeURIComponent(chatId.value)}${
      target
        ? `?source=${encodeURIComponent(target.source)}&chunk=${encodeURIComponent(target.chunk)}`
        : ''
    }`;
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  function findPreviousUserQuestion(index: number): string {
    for (let i = index - 1; i >= 0; i -= 1) {
      const candidate = messages.value[i];
      if (candidate.role === 'user' && candidate.content.trim()) {
        return candidate.content.trim();
      }
    }
    return '';
  }

  async function rateAnswer(index: number, message: ChatMessage, rating: number): Promise<void> {
    if (message.role !== 'assistant') {
      return;
    }
    if (!auth.canUseRemoteSync.value) {
      ElMessage.warning('请先完成鉴权后再提交反馈');
      return;
    }
    if (answerFeedbackMap.value[message.id]) {
      ElMessage.info('该回答已评分');
      return;
    }
    answerFeedbackLoading.value = {
      ...answerFeedbackLoading.value,
      [message.id]: true,
    };
    try {
      await submitAnswerFeedback(
        {
          chatId: chatId.value,
          sessionId: activeSession.value.id,
          branchId: activeBranch.value.id,
          messageId: message.id,
          rating,
          question: findPreviousUserQuestion(index),
          answer: message.content,
          comment: rating >= 4 ? '回答有帮助' : '回答需要改进',
        },
        auth.authContext(),
      );
      answerFeedbackMap.value = {
        ...answerFeedbackMap.value,
        [message.id]: rating,
      };
      ElMessage.success('反馈已提交并回灌评测集');
    } catch (error) {
      if (isGuestForbidden(error, auth.guestMode.value)) {
        // 游客 403：引导卡片替换裸错误提示
        reportGuestRestriction('回答反馈');
      } else {
        const tip = toUserMessage(error, '反馈提交失败');
        ElMessage.error(tip);
      }
    } finally {
      answerFeedbackLoading.value = {
        ...answerFeedbackLoading.value,
        [message.id]: false,
      };
    }
  }

  function stopGenerating(): void {
    currentAbortController.value?.abort();
  }

  function sanitizeMessageStates(): void {
    messages.value = messages.value.map((message) => ({
      ...message,
      state:
        message.state === 'pending' || message.state === 'streaming'
          ? 'done'
          : message.state || 'done',
    }));
  }

  async function ask(question: string, appendUser: boolean): Promise<void> {
    if (!question.trim() || sending.value) {
      return;
    }

    sanitizeMessageStates();

    const assistantMsg: ChatMessage = {
      ...createMessage('assistant', ''),
      citations: [],
      citationCards: [],
      evidence: [],
      matchedQuestion: question,
      state: 'pending',
    };

    if (appendUser) {
      messages.value.push(createMessage('user', question));
    }
    messages.value.push(assistantMsg);
    const assistantIndex = messages.value.length - 1;

    traceSteps.value = [];
    sending.value = true;
    isStreamingResponse.value = streaming.value;
    prompt.value = '';
    streamPhase.value = 'thinking';
    streamStatusDetail.value = '模型正在准备响应';

    syncCurrentSessionBranch();
    persistState();
    await ui.scrollToBottom(true);

    const controller = new AbortController();
    currentAbortController.value = controller;

    try {
      if (streaming.value) {
        let streamError = '';
        await streamReactChat(
          {
            prompt: question,
            chatId: chatId.value,
            modelProfile: modelProfile.value,
          },
          auth.authContext(),
          (event, payload) => {
            if (event === 'trace') {
              const step = payload as ReactTraceStep;
              upsertTrace(step);
              streamPhase.value = 'tool';
              streamStatusDetail.value = `调用工具: ${step.action}`;
              return;
            }

            if (event === 'token') {
              const tokenEvent = payload as ReactTokenEvent;
              messages.value[assistantIndex].state = 'streaming';
              messages.value[assistantIndex].content += tokenEvent.token ?? '';
              streamPhase.value = 'streaming';
              streamStatusDetail.value = '正在生成文本';
              void ui.scrollToBottom();
              return;
            }

            if (event === 'done') {
              const done = payload as ReactChatResponse & {
                citationCards?: ChatCitationCard[];
                retrievalTrace?: RetrievalTrace;
              };
              if (done.trace?.length) {
                traceSteps.value = done.trace;
              }
              if (done.answer?.trim()) {
                messages.value[assistantIndex].content = done.answer;
              }
              messages.value[assistantIndex].citations = Array.isArray(done.citations)
                ? done.citations.map((item) => String(item).trim()).filter(Boolean)
                : [];
              messages.value[assistantIndex].citationCards = Array.isArray(done.citationCards)
                ? done.citationCards.filter((card) => card && card.file && card.snippet)
                : [];
              messages.value[assistantIndex].retrievalTrace = done.retrievalTrace;
              messages.value[assistantIndex].evidence = Array.isArray(done.evidence)
                ? done.evidence.map((item) => String(item).trim()).filter(Boolean)
                : [];
              messages.value[assistantIndex].state = 'done';
              streamPhase.value = 'done';
              streamStatusDetail.value = '响应已完成';
              return;
            }

            if (event === 'error') {
              const err = payload as ReactErrorEvent;
              streamError = err.message || '回答生成失败，请稍后重试';
            }
          },
          controller.signal,
        );

        if (streamError) {
          throw new Error(streamError);
        }
      } else {
        const result = await reactChat(
          {
            prompt: question,
            chatId: chatId.value,
            modelProfile: modelProfile.value,
          },
          auth.authContext(),
          controller.signal,
        );
        traceSteps.value = result.trace ?? [];
        messages.value[assistantIndex].content = result.answer || '模型没有返回内容';
        const withCards = result as ReactChatResponse & {
          citationCards?: ChatCitationCard[];
          retrievalTrace?: RetrievalTrace;
        };
        messages.value[assistantIndex].citations = Array.isArray(result.citations)
          ? result.citations.map((item) => String(item).trim()).filter(Boolean)
          : [];
        messages.value[assistantIndex].citationCards = Array.isArray(withCards.citationCards)
          ? withCards.citationCards.filter((card) => card && card.file && card.snippet)
          : [];
        messages.value[assistantIndex].evidence = Array.isArray(result.evidence)
          ? result.evidence.map((item) => String(item).trim()).filter(Boolean)
          : [];
        messages.value[assistantIndex].retrievalTrace = withCards.retrievalTrace;
        messages.value[assistantIndex].state = 'done';
        streamPhase.value = 'done';
        streamStatusDetail.value = '响应已完成';
        retriedAuth.value = false;
      }
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        ElMessage.info('已停止输出');
        if (!messages.value[assistantIndex].content.trim()) {
          messages.value[assistantIndex].content = '输出已手动停止。';
        }
        messages.value[assistantIndex].state = 'stopped';
        streamPhase.value = 'stopped';
        streamStatusDetail.value = '你手动停止了本次输出';
      } else {
        const message = toUserMessage(error, '本次提问处理失败');
        // 凭证过期：清 token → 游客自动登录 → 自动重试一次（对用户无感）
        if (errorStatus(error) === 401 && !retriedAuth.value) {
          retriedAuth.value = true;
          if (await auth.ensureCredential()) {
            ElMessage.info('登录态已过期，已自动恢复，正在重试');
            messages.value.splice(assistantIndex, 1);
            sending.value = false;
            isStreamingResponse.value = false;
            currentAbortController.value = null;
            await ask(question, false);
            return;
          }
        }
        // 游客 403：功能受限，引导卡片替换裸错误提示；气泡内保留一句简短说明
        if (isGuestForbidden(error, auth.guestMode.value)) {
          reportGuestRestriction('在线问答');
          messages.value[assistantIndex].content = '当前为游客模式，此操作需要登录后使用。';
          messages.value[assistantIndex].state = 'error';
          streamPhase.value = 'error';
          streamStatusDetail.value = '游客模式功能受限';
        } else {
          messages.value[assistantIndex].content = message;
          messages.value[assistantIndex].state = 'error';
          streamPhase.value = 'error';
          streamStatusDetail.value = message;
          ElMessage.error(message);
        }
      }
    } finally {
      sending.value = false;
      isStreamingResponse.value = false;
      currentAbortController.value = null;

      syncCurrentSessionBranch();
      persistState();
      await ui.scrollToBottom(true);

      if (
        streamPhase.value === 'done' ||
        streamPhase.value === 'error' ||
        streamPhase.value === 'stopped'
      ) {
        scheduleStreamReset();
      }
    }
  }

  async function send(): Promise<void> {
    const question = prompt.value.trim();
    await ask(question, true);
  }

  async function regenerateFrom(assistantIndex: number): Promise<void> {
    for (let i = assistantIndex - 1; i >= 0; i -= 1) {
      const candidate = messages.value[i];
      if (candidate.role === 'user' && candidate.content.trim()) {
        syncCurrentSessionBranch();
        const session = activeSession.value;
        const current = activeBranch.value;
        const baseMessages = messages.value.slice(0, i);
        const branch = forkBranch(
          `${deriveTitle(candidate.content)} · retry`,
          baseMessages,
          current.id,
          candidate.id,
        );
        session.branches.unshift(branch);
        session.activeBranchId = branch.id;
        loadSession(session.id);
        persistState();
        await ask(candidate.content, true);
        return;
      }
    }

    ElMessage.warning('没有找到可重试的用户问题');
  }

  function startEditMessage(message: ChatMessage): void {
    if (message.role !== 'user') {
      return;
    }

    editingMessageId.value = message.id;
    editingMessageDraft.value = message.content;
  }

  function cancelEditMessage(): void {
    editingMessageId.value = null;
    editingMessageDraft.value = '';
  }

  async function submitEditAndResend(messageIndex: number, messageId: string): Promise<void> {
    const question = editingMessageDraft.value.trim();
    if (!question) {
      return;
    }

    if (sending.value) {
      ElMessage.warning('请等待当前请求完成');
      return;
    }

    syncCurrentSessionBranch();

    const session = activeSession.value;
    const current = activeBranch.value;
    const baseMessages = messages.value.slice(0, Math.max(0, messageIndex));
    const branch = forkBranch(
      `${deriveTitle(question)} · edit`,
      baseMessages,
      current.id,
      messageId,
    );

    session.branches.unshift(branch);
    session.activeBranchId = branch.id;
    loadSession(session.id);

    cancelEditMessage();
    persistState();

    await ask(question, true);
  }

  /** 逃生门之外的会话清空：保留会话本身，仅重置消息/trace/反馈等当前视图状态。 */
  function clearConversation(): void {
    messages.value = [createMessage('assistant', '会话已重置。你可以继续发问。')];
    traceSteps.value = [];
    answerFeedbackMap.value = {};
    answerFeedbackLoading.value = {};
    prompt.value = '';
    streamPhase.value = 'idle';
    streamStatusDetail.value = '';
    syncCurrentSessionBranch();
    persistState();
  }

  // 流式状态复位定时器随组件卸载清理（拆分前在 App.vue 的 onBeforeUnmount 中处理）
  onBeforeUnmount(() => {
    if (streamResetTimer) {
      window.clearTimeout(streamResetTimer);
      streamResetTimer = null;
    }
  });

  return {
    // 状态
    sending,
    isStreamingResponse,
    streamPhase,
    streamStatusDetail,
    expandedCitationCards,
    expandedTraceOpen,
    answerFeedbackMap,
    answerFeedbackLoading,
    // 计算属性
    isEmptyConversation,
    streamStatusLabel,
    streamStatusTagType,
    traceDurationMs,
    // 交互
    toggleCitationCard,
    toggleTraceOpen,
    openCitation,
    rateAnswer,
    stopGenerating,
    send,
    regenerateFrom,
    startEditMessage,
    cancelEditMessage,
    submitEditAndResend,
    clearConversation,
  };
}

export type ChatEngine = ReturnType<typeof useChatEngine>;
