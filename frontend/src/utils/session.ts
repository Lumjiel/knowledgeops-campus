/**
 * 会话/分支/消息的纯函数工具：工厂与规范化。
 * 从 App.vue 抽出，无副作用（localStorage 除外），供 useSessionStore 等共用。
 */
import type { ChatCitationCard, ChatMessage } from '../types/chat';
import type { ReactTraceStep } from '../types/react';
import type { SessionBranch, SessionRecord } from '../types/session';

export const DEFAULT_SYSTEM_MESSAGE =
  '你好，我是有章。校园规章相关的问题都可以问我，回答会附上制度原文出处。';
export const DEFAULT_WORKSPACE = 'default';

export function createChatId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `react-${Date.now()}-${suffix}`;
}

export function createBranchId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `branch-${Date.now()}-${suffix}`;
}

export function createMessage(role: ChatMessage['role'], content: string): ChatMessage {
  return {
    id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createdAt: Date.now(),
    state: 'done',
  };
}

export function normalizeMessage(raw: unknown): ChatMessage {
  const candidate = (raw ?? {}) as Partial<ChatMessage>;
  const role = candidate.role === 'assistant' ? 'assistant' : 'user';
  return {
    id: candidate.id || `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content: typeof candidate.content === 'string' ? candidate.content : '',
    createdAt: typeof candidate.createdAt === 'number' ? candidate.createdAt : Date.now(),
    citations: Array.isArray(candidate.citations)
      ? candidate.citations.map((item) => String(item).trim()).filter(Boolean)
      : [],
    citationCards: Array.isArray(candidate.citationCards)
      ? candidate.citationCards.filter(
          (card): card is ChatCitationCard =>
            Boolean(card) && typeof card.file === 'string' && typeof card.snippet === 'string',
        )
      : [],
    evidence: Array.isArray(candidate.evidence)
      ? candidate.evidence.map((item) => String(item).trim()).filter(Boolean)
      : [],
    state: candidate.state || 'done',
  };
}

export function deriveTitle(text: string): string {
  const clean = text.trim().replace(/\s+/g, ' ');
  if (!clean) {
    return '新会话';
  }
  return clean.length > 28 ? `${clean.slice(0, 28)}...` : clean;
}

export function createRootBranch(): SessionBranch {
  return {
    id: createBranchId(),
    title: '主分支',
    parentBranchId: null,
    parentMessageId: null,
    updatedAt: Date.now(),
    messages: [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)],
    traceSteps: [],
  };
}

export function normalizeBranch(raw: unknown): SessionBranch {
  const candidate = (raw ?? {}) as Partial<SessionBranch>;
  const messages = Array.isArray(candidate.messages)
    ? candidate.messages.map(normalizeMessage)
    : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

  return {
    id: candidate.id || createBranchId(),
    title: candidate.title || '分支',
    parentBranchId: candidate.parentBranchId ?? null,
    parentMessageId: candidate.parentMessageId ?? null,
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    messages,
    traceSteps: Array.isArray(candidate.traceSteps) ? candidate.traceSteps : [],
  };
}

export function normalizeSession(raw: unknown): SessionRecord {
  const candidate = (raw ?? {}) as Record<string, unknown>;
  let branches: SessionBranch[] = [];

  if (Array.isArray(candidate.branches) && candidate.branches.length > 0) {
    branches = candidate.branches.map((item) => normalizeBranch(item));
  } else {
    const fallbackMessages = Array.isArray(candidate.messages)
      ? candidate.messages.map(normalizeMessage)
      : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

    branches = [
      {
        id: createBranchId(),
        title: '主分支',
        parentBranchId: null,
        parentMessageId: null,
        updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
        messages: fallbackMessages,
        traceSteps: Array.isArray(candidate.traceSteps)
          ? (candidate.traceSteps as ReactTraceStep[])
          : [],
      },
    ];
  }

  const activeBranchId =
    typeof candidate.activeBranchId === 'string' ? candidate.activeBranchId : branches[0].id;

  return {
    id: typeof candidate.id === 'string' ? candidate.id : createChatId(),
    title: typeof candidate.title === 'string' ? candidate.title : '新会话',
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    modelProfile: typeof candidate.modelProfile === 'string' ? candidate.modelProfile : 'balanced',
    streaming: Boolean(candidate.streaming ?? true),
    pinned: Boolean(candidate.pinned),
    archived: Boolean(candidate.archived),
    workspaceId:
      typeof candidate.workspaceId === 'string' ? candidate.workspaceId : DEFAULT_WORKSPACE,
    activeBranchId,
    branches,
  };
}

export function createSession(): SessionRecord {
  const id = createChatId();
  const rootBranch = createRootBranch();

  return {
    id,
    title: '新会话',
    updatedAt: Date.now(),
    modelProfile: 'balanced',
    streaming: true,
    pinned: false,
    archived: false,
    workspaceId: DEFAULT_WORKSPACE,
    activeBranchId: rootBranch.id,
    branches: [rootBranch],
  };
}

export function shortId(id: string): string {
  return id.slice(0, 10);
}
