/**
 * 会话仓：会话/分支/当前视图镜像状态（messages、traceSteps 等），
 * 以及会话增删、置顶归档、分支 fork/对比/合并与云端同步。
 * 从 App.vue 抽出；UI 过滤条件（搜索、工作区、归档）与侧栏开合由 App 注入。
 */
import { ElMessage } from 'element-plus';
import { computed, ref } from 'vue';
import type { Ref } from 'vue';
import {
  compareSessionBranches,
  listSessionStates,
  mergeSessionBranches,
  saveSessionState,
  setSessionArchived,
  setSessionPinned,
} from '../api/client';
import type { ChatMessage } from '../types/chat';
import type { ReactTraceStep, SessionState } from '../types/react';
import type { BranchTreeItem, SessionBranch, SessionRecord } from '../types/session';
import { toUserMessage } from '../utils/errors';
import {
  DEFAULT_WORKSPACE,
  createBranchId,
  createRootBranch,
  createSession,
  deriveTitle,
  normalizeSession,
} from '../utils/session';
import { STORAGE_KEY } from '../utils/storage';
import type { AuthStore } from './useAuth';

interface UseSessionStoreOptions {
  bootstrap: Record<string, unknown>;
  persistState: () => void;
  auth: AuthStore;
  sessionSearch: Ref<string>;
  workspaceFilter: Ref<string>;
  showArchivedSessions: Ref<boolean>;
  sidebarOpen: Ref<boolean>;
  ui: {
    isMobileViewport: () => boolean;
    resetMessageHeights: () => void;
    scrollToBottom: (force?: boolean) => Promise<void> | void;
  };
}

/** 会话恢复兜底：坏数据不允许阻断启动，最多丢本地草稿（云端已同步的不受影响）。 */
function safeInitSessions(source: Record<string, unknown>): SessionRecord[] {
  try {
    if (Array.isArray(source.sessions) && source.sessions.length > 0) {
      const normalized = (source.sessions as unknown[]).map((item) => normalizeSession(item));
      if (normalized.length > 0) {
        return normalized;
      }
    }
  } catch {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore
    }
  }
  return [createSession()];
}

export function useSessionStore(options: UseSessionStoreOptions) {
  const { bootstrap, persistState, auth } = options;
  const { sessionSearch, workspaceFilter, showArchivedSessions, sidebarOpen, ui } = options;

  const sessions = ref<SessionRecord[]>(safeInitSessions(bootstrap));

  const bootActiveSessionId =
    typeof bootstrap.activeSessionId === 'string' ? bootstrap.activeSessionId : '';
  const activeSessionId = ref(
    sessions.value.some((session) => session.id === bootActiveSessionId)
      ? bootActiveSessionId
      : sessions.value[0].id,
  );

  const activeSession = computed(() => {
    const found = sessions.value.find((item) => item.id === activeSessionId.value);
    return found ?? sessions.value[0];
  });

  const activeBranch = computed(() => {
    const session = activeSession.value;
    return (
      session.branches.find((branch) => branch.id === session.activeBranchId) ?? session.branches[0]
    );
  });

  // 当前会话在视图层的镜像状态：切换会话/分支时由 loadSession 重填
  const chatId = ref(activeSession.value.id);
  const modelProfile = ref(activeSession.value.modelProfile);
  const streaming = ref(activeSession.value.streaming);
  const messages = ref<ChatMessage[]>([...activeBranch.value.messages]);
  const traceSteps = ref<ReactTraceStep[]>([...activeBranch.value.traceSteps]);
  const prompt = ref('');
  const editingMessageId = ref<string | null>(null);
  const editingMessageDraft = ref('');
  const cloudSyncing = ref(false);

  const sessionCount = computed(() => sessions.value.length);

  const workspaceOptions = computed(() => {
    const workspaceSet = new Set<string>([DEFAULT_WORKSPACE]);
    sessions.value.forEach((session) => {
      workspaceSet.add(session.workspaceId || DEFAULT_WORKSPACE);
    });
    return [...workspaceSet].sort((a, b) => a.localeCompare(b, 'zh-CN'));
  });

  const activeWorkspaceId = computed({
    get: () => activeSession.value.workspaceId,
    set: (value: string) => {
      activeSession.value.workspaceId = value || DEFAULT_WORKSPACE;
      persistState();
    },
  });

  const orderedSessions = computed(() =>
    [...sessions.value].sort((a, b) => {
      if (a.pinned !== b.pinned) {
        return Number(b.pinned) - Number(a.pinned);
      }
      return b.updatedAt - a.updatedAt;
    }),
  );

  const filteredSessions = computed(() => {
    const keyword = sessionSearch.value.trim().toLowerCase();
    return orderedSessions.value.filter((session) => {
      if (!showArchivedSessions.value && session.archived) {
        return false;
      }

      if (workspaceFilter.value !== 'all' && session.workspaceId !== workspaceFilter.value) {
        return false;
      }

      if (!keyword) {
        return true;
      }

      return (
        session.title.toLowerCase().includes(keyword) || session.id.toLowerCase().includes(keyword)
      );
    });
  });

  const branchTreeItems = computed<BranchTreeItem[]>(() => {
    const session = activeSession.value;
    const children = new Map<string | null, SessionBranch[]>();

    session.branches.forEach((branch) => {
      const key = branch.parentBranchId ?? null;
      if (!children.has(key)) {
        children.set(key, []);
      }
      children.get(key)?.push(branch);
    });

    children.forEach((list) => {
      list.sort((a, b) => b.updatedAt - a.updatedAt);
    });

    const result: BranchTreeItem[] = [];

    function dfs(parentId: string | null, depth: number): void {
      const list = children.get(parentId) ?? [];
      list.forEach((branch) => {
        result.push({ branch, depth });
        dfs(branch.id, depth + 1);
      });
    }

    dfs(null, 0);
    return result;
  });

  function getSession(sessionId: string): SessionRecord | undefined {
    return sessions.value.find((item) => item.id === sessionId);
  }

  function getBranch(session: SessionRecord, branchId: string): SessionBranch | undefined {
    return session.branches.find((branch) => branch.id === branchId);
  }

  function syncCurrentSessionBranch(): void {
    const session = getSession(activeSessionId.value);
    if (!session) {
      return;
    }

    const branch = getBranch(session, session.activeBranchId);
    if (!branch) {
      return;
    }

    session.modelProfile = modelProfile.value;
    session.streaming = streaming.value;

    branch.messages = [...messages.value];
    branch.traceSteps = [...traceSteps.value];
    branch.updatedAt = Date.now();

    const firstUser = branch.messages.find((item) => item.role === 'user');
    if (firstUser?.content?.trim()) {
      branch.title = deriveTitle(firstUser.content);
      session.title = deriveTitle(firstUser.content);
    }

    session.updatedAt = Date.now();
  }

  function loadSession(sessionId: string): void {
    const session = getSession(sessionId);
    if (!session) {
      return;
    }

    activeSessionId.value = session.id;
    chatId.value = session.id;
    modelProfile.value = session.modelProfile;
    streaming.value = session.streaming;

    const branch = getBranch(session, session.activeBranchId) ?? session.branches[0];
    if (!branch) {
      const rootBranch = createRootBranch();
      session.branches = [rootBranch];
      session.activeBranchId = rootBranch.id;
      messages.value = [...rootBranch.messages].map((message) =>
        message.role === 'assistant' && message.state && message.state !== 'done'
          ? { ...message, state: 'done' }
          : message,
      );
      traceSteps.value = [...rootBranch.traceSteps];
    } else {
      messages.value = [...branch.messages].map((message) =>
        message.role === 'assistant' && message.state && message.state !== 'done'
          ? { ...message, state: 'done' }
          : message,
      );
      traceSteps.value = [...branch.traceSteps];
    }

    ui.resetMessageHeights();
    prompt.value = '';
    editingMessageId.value = null;
    editingMessageDraft.value = '';
    void ui.scrollToBottom(true);
  }

  function switchSession(sessionId: string): void {
    if (ui.isMobileViewport()) {
      sidebarOpen.value = false;
    }
    if (sessionId === activeSessionId.value) {
      return;
    }

    syncCurrentSessionBranch();
    loadSession(sessionId);
    persistState();
  }

  function createAndSwitchSession(): void {
    syncCurrentSessionBranch();
    const session = createSession();
    sessions.value.unshift(session);
    loadSession(session.id);
    persistState();
  }

  function removeSession(sessionId: string): void {
    if (sessions.value.length <= 1) {
      ElMessage.warning('至少保留一个会话');
      return;
    }

    syncCurrentSessionBranch();
    const filtered = sessions.value.filter((item) => item.id !== sessionId);
    sessions.value = filtered;

    if (activeSessionId.value === sessionId) {
      const next = filtered.find((item) => !item.archived) ?? filtered[0];
      loadSession(next.id);
    }

    persistState();
  }

  async function toggleSessionPin(sessionId: string): Promise<void> {
    const session = getSession(sessionId);
    if (!session) {
      return;
    }
    session.pinned = !session.pinned;
    session.updatedAt = Date.now();
    persistState();
    if (auth.canUseRemoteSync.value) {
      try {
        await setSessionPinned(sessionId, session.pinned, auth.authContext());
      } catch (error) {
        const message = toUserMessage(error, '会话置顶同步失败');
        ElMessage.error(message);
      }
    }
  }

  async function toggleSessionArchive(sessionId: string): Promise<void> {
    const session = getSession(sessionId);
    if (!session) {
      return;
    }

    session.archived = !session.archived;
    session.updatedAt = Date.now();

    if (session.archived && !showArchivedSessions.value && activeSessionId.value === sessionId) {
      const next =
        sessions.value.find((item) => item.id !== sessionId && !item.archived) ??
        sessions.value.find((item) => item.id !== sessionId) ??
        createSession();

      if (!sessions.value.find((item) => item.id === next.id)) {
        sessions.value.unshift(next);
      }

      loadSession(next.id);
    }

    persistState();
    if (auth.canUseRemoteSync.value) {
      try {
        await setSessionArchived(sessionId, session.archived, auth.authContext());
      } catch (error) {
        const message = toUserMessage(error, '会话归档同步失败');
        ElMessage.error(message);
      }
    }
  }

  function switchBranch(branchId: string): void {
    const session = activeSession.value;
    if (session.activeBranchId === branchId) {
      return;
    }

    syncCurrentSessionBranch();
    session.activeBranchId = branchId;
    loadSession(session.id);
    persistState();
  }

  function forkBranch(
    title: string,
    baseMessages: ChatMessage[],
    parentBranchId: string | null,
    parentMessageId: string | null,
  ): SessionBranch {
    return {
      id: createBranchId(),
      title,
      parentBranchId,
      parentMessageId,
      updatedAt: Date.now(),
      messages: [...baseMessages],
      traceSteps: [],
    };
  }

  function forkFromCurrent(): void {
    const session = activeSession.value;
    const current = activeBranch.value;

    const branch = forkBranch(`${current.title} · fork`, [...messages.value], current.id, null);

    session.branches.unshift(branch);
    session.activeBranchId = branch.id;
    loadSession(session.id);
    persistState();
    ElMessage.success('已创建分支');
  }

  async function compareWithParent(): Promise<void> {
    const current = activeBranch.value;
    if (!current?.parentBranchId) {
      ElMessage.warning('当前分支没有父分支可对比');
      return;
    }
    if (!auth.canUseRemoteSync.value) {
      ElMessage.warning('请先完成鉴权后再执行云端分支对比');
      return;
    }
    syncCurrentSessionBranch();
    try {
      await syncActiveSessionToCloud();
      const result = await compareSessionBranches(
        activeSession.value.id,
        {
          sourceBranchId: current.id,
          targetBranchId: current.parentBranchId,
        },
        auth.authContext(),
      );
      ElMessage.success(
        `对比完成：公共 ${result.commonMessageCount}，当前独有 ${result.sourceOnlyCount}，父分支独有 ${result.targetOnlyCount}`,
      );
    } catch (error) {
      const message = toUserMessage(error, '分支对比失败');
      ElMessage.error(message);
    }
  }

  async function mergeIntoParent(): Promise<void> {
    const current = activeBranch.value;
    if (!current?.parentBranchId) {
      ElMessage.warning('当前分支没有父分支可合并');
      return;
    }
    if (!auth.canUseRemoteSync.value) {
      ElMessage.warning('请先完成鉴权后再执行云端分支合并');
      return;
    }
    syncCurrentSessionBranch();
    try {
      await syncActiveSessionToCloud();
      const result = await mergeSessionBranches(
        activeSession.value.id,
        {
          sourceBranchId: current.id,
          targetBranchId: current.parentBranchId,
          title: `${current.title} -> ${current.parentBranchId} merge`,
        },
        auth.authContext(),
      );
      const normalized = normalizeRemoteSession(result.session);
      const index = sessions.value.findIndex((item) => item.id === normalized.id);
      if (index >= 0) {
        sessions.value[index] = normalized;
      } else {
        sessions.value.unshift(normalized);
      }
      loadSession(normalized.id);
      persistState();
      ElMessage.success(`合并完成，分支消息数 ${result.mergedMessageCount}`);
    } catch (error) {
      const message = toUserMessage(error, '分支合并失败');
      ElMessage.error(message);
    }
  }

  function normalizeRemoteSession(raw: unknown): SessionRecord {
    return normalizeSession(raw);
  }

  async function loadSessionsFromCloud(): Promise<void> {
    if (!auth.canUseRemoteSync.value) {
      ElMessage.warning('请先完成鉴权后再同步');
      return;
    }
    cloudSyncing.value = true;
    try {
      const page = await listSessionStates(auth.authContext(), {
        page: 1,
        pageSize: 200,
        includeArchived: true,
      });
      if (Array.isArray(page.items) && page.items.length > 0) {
        sessions.value = page.items.map((item) => normalizeRemoteSession(item));
        const current =
          sessions.value.find((item) => item.id === activeSessionId.value) ?? sessions.value[0];
        loadSession(current.id);
        persistState();
      }
      await auth.refreshCostSummary();
      ElMessage.success('已从云端加载会话');
    } catch (error) {
      const message = toUserMessage(error, '云端拉取失败');
      ElMessage.error(message);
    } finally {
      cloudSyncing.value = false;
    }
  }

  async function syncActiveSessionToCloud(): Promise<void> {
    if (!auth.canUseRemoteSync.value) {
      ElMessage.warning('请先完成鉴权后再同步');
      return;
    }
    syncCurrentSessionBranch();
    cloudSyncing.value = true;
    try {
      const saved = await saveSessionState(
        activeSession.value as unknown as SessionState,
        auth.authContext(),
      );
      const normalized = normalizeRemoteSession(saved);
      const index = sessions.value.findIndex((item) => item.id === normalized.id);
      if (index >= 0) {
        sessions.value[index] = normalized;
      } else {
        sessions.value.unshift(normalized);
      }
      loadSession(normalized.id);
      persistState();
      await auth.refreshCostSummary();
      ElMessage.success('当前会话已保存到云端');
    } catch (error) {
      const message = toUserMessage(error, '云端保存失败');
      ElMessage.error(message);
    } finally {
      cloudSyncing.value = false;
    }
  }

  return {
    // 状态
    sessions,
    activeSessionId,
    activeSession,
    activeBranch,
    chatId,
    modelProfile,
    streaming,
    messages,
    traceSteps,
    prompt,
    editingMessageId,
    editingMessageDraft,
    cloudSyncing,
    // 计算属性
    sessionCount,
    workspaceOptions,
    activeWorkspaceId,
    orderedSessions,
    filteredSessions,
    branchTreeItems,
    // 会话操作
    getSession,
    getBranch,
    syncCurrentSessionBranch,
    loadSession,
    switchSession,
    createAndSwitchSession,
    removeSession,
    toggleSessionPin,
    toggleSessionArchive,
    // 分支操作
    switchBranch,
    forkBranch,
    forkFromCurrent,
    compareWithParent,
    mergeIntoParent,
    // 云端同步
    loadSessionsFromCloud,
    syncActiveSessionToCloud,
  };
}

export type SessionStore = ReturnType<typeof useSessionStore>;
