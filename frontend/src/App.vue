<template>
  <div class="app-shell" :class="{ 'sidebar-closed': !sidebarOpen, 'sidebar-open': sidebarOpen }">
    <Sidebar
      v-model:search="sessionSearch"
      :sessions="filteredSessions"
      :session-count="sessionCount"
      :active-session-id="activeSessionId"
      :active-session="activeSession"
      :active-branch="activeBranch"
      :branch-tree-items="branchTreeItems"
      :view="activeView"
      @create-session="createAndSwitchSession"
      @switch-session="switchSession"
      @toggle-pin="toggleSessionPin"
      @toggle-archive="toggleSessionArchive"
      @remove-session="removeSession"
      @switch-branch="switchBranch"
      @fork-from-current="forkFromCurrent"
      @compare-with-parent="compareWithParent"
      @merge-into-parent="mergeIntoParent"
      @activate-view="activateView"
    />
    <div
      v-if="sidebarOpen"
      class="sidebar-backdrop"
      aria-hidden="true"
      @click="sidebarOpen = false"
    ></div>

    <main class="workspace">
      <header class="workspace-head">
        <button
          type="button"
          class="sidebar-toggle"
          aria-label="会话列表"
          @click="sidebarOpen = !sidebarOpen"
        >
          ☰
        </button>
        <div v-if="activeView === 'chat'">
          <p class="workspace-kicker">当前会话</p>
          <h2>{{ activeSession?.title || '新会话' }}</h2>
          <p class="workspace-sub">
            {{ modelProfile }} · {{ streaming ? 'SSE 流式' : 'JSON 单次' }}
          </p>
        </div>
        <div v-else>
          <p class="workspace-kicker">检索评测</p>
          <h2>评测工作台</h2>
          <p class="workspace-sub">数据集 / 运行 / 基线对比</p>
        </div>
        <div v-if="activeView === 'chat'" class="head-actions">
          <el-tag v-if="guestMode" effect="plain" class="guest-chip">游客模式</el-tag>
          <el-tag :type="streamStatusTagType" effect="plain">{{ streamStatusLabel }}</el-tag>
          <span class="stream-detail">{{ streamStatusDetail }}</span>
          <span v-if="costSummary" class="stream-detail"
            >成本: 本月 ${{ costSummary.monthCostUsd.toFixed(4) }} / 预算 ${{
              costSummary.monthlyBudgetUsd.toFixed(4)
            }}</span
          >
          <el-button size="small" text @click="settingsOpen = true">设置</el-button>
        </div>
        <!-- 评测视图的操作按钮由 EvaluationPanel 组件自带（loadDatasets/runSelected/exportReport/markBaseline） -->
      </header>

      <template v-if="activeView === 'chat'">
        <MemoryHintBar ref="memoryHintRef" :auth-context="authContext" :can-load="!guestMode" />
        <MessageList
          ref="messageListRef"
          :messages="messages"
          :hydrating="hydrating"
          :is-empty-conversation="isEmptyConversation"
          :sending="sending"
          :is-streaming-response="isStreamingResponse"
          :stream-status-label="streamStatusLabel"
          :stream-status-detail="streamStatusDetail"
          :trace-steps="traceSteps"
          :trace-duration-ms="traceDurationMs"
          :expanded-citation-cards="expandedCitationCards"
          :expanded-trace-open="expandedTraceOpen"
          :editing-message-id="editingMessageId"
          v-model:editing-message-draft="editingMessageDraft"
          :answer-feedback-map="answerFeedbackMap"
          :answer-feedback-loading="answerFeedbackLoading"
          @toggle-citation-card="toggleCitationCard"
          @toggle-trace-open="toggleTraceOpen"
          @open-citation="openCitation"
          @start-edit="startEditMessage"
          @cancel-edit="cancelEditMessage"
          @submit-edit="submitEditAndResend"
          @regenerate-from="regenerateFrom"
          @rate-answer="rateAnswer"
        />

        <ChatInput
          v-model:prompt="prompt"
          :sending="sending"
          :guest-mode="guestMode"
          :is-empty-conversation="isEmptyConversation"
          :session-id="activeSessionId"
          @send="send"
          @stop="stopGenerating"
        />
      </template>

      <!-- 评测工作台：状态自持（v-show 常驻，避免切换视图丢失已加载的评测数据） -->
      <EvaluationPanel
        v-show="activeView === 'evaluation'"
        ref="evalPanelRef"
        v-model:selected-dataset-id="evalSelectedDatasetId"
        :auth-context="authContext"
        :model-profile="modelProfile"
        :guest-mode="guestMode"
        @persist="persistState"
      />
    </main>

    <!-- 游客 403 功能受限引导卡片（状态在 useGuestRestriction 单例中） -->
    <GuestRestrictionCard />

    <SettingsDialog
      v-model:open="settingsOpen"
      :is-mobile="isMobile"
      v-model:api-key="apiKeyInput"
      v-model:tenant="tenantInput"
      v-model:model-profile="modelProfile"
      v-model:streaming="streaming"
      :refresh-token="refreshToken"
      :workspace-options="workspaceOptions"
      v-model:active-workspace-id="activeWorkspaceId"
      v-model:workspace-filter="workspaceFilter"
      v-model:show-archived-sessions="showArchivedSessions"
      v-model:workspace-draft="workspaceDraft"
      :auth-loading="authLoading"
      :refreshing="refreshing"
      :cloud-syncing="cloudSyncing"
      :can-use-remote-sync="canUseRemoteSync"
      @login="handleLogin"
      @refresh-auth="handleRefresh"
      @clear-auth="clearAuth"
      @create-workspace="createWorkspace"
      @pull-cloud="loadSessionsFromCloud"
      @sync-cloud="syncActiveSessionToCloud"
      @clear-conversation="clearConversation"
      @reset-local="resetLocalState"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * App.vue（应用外壳）：只负责布局组装、持久化与各模块之间的装配。
 *
 * 组件拆分清单（2026-11-04，自 2933 行拆出）：
 * - components/EvaluationPanel.vue —— 评测工作台（原任务所述大块之一；代码中并无「文档库面板」，
 *   语义最接近的即评测工作台）
 * - components/SettingsDialog.vue  —— 设置弹窗
 * - components/MemoryHintBar.vue   —— A6 记忆提示条
 * - composables/useAuth.ts         —— 鉴权 / 游客模式 / 租户成本
 * - composables/useSessionStore.ts —— 会话与分支仓、云端同步
 * - composables/useChatEngine.ts   —— 流式问答 / 编辑重发 / 评分反馈 / 引用跳转
 * - utils/session.ts、utils/storage.ts —— 纯函数与本地存储
 * - api/authRecovery.ts            —— 401 统一恢复（静默刷新 / 清理登录态引导登录）
 * - styles/app-shell.css           —— 应用骨架样式（scoped 引入）
 */
import { ElMessage } from 'element-plus';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import ChatInput from './components/ChatInput.vue';
import EvaluationPanel from './components/EvaluationPanel.vue';
import GuestRestrictionCard from './components/GuestRestrictionCard.vue';
import MemoryHintBar from './components/MemoryHintBar.vue';
import MessageList from './components/MessageList.vue';
import SettingsDialog from './components/SettingsDialog.vue';
import Sidebar from './components/Sidebar.vue';
import { configureAuthRecovery } from './api/authRecovery';
import { useAuth } from './composables/useAuth';
import { useChatEngine } from './composables/useChatEngine';
import {
  bindRestrictionLoginAction,
  dismissGuestRestriction,
} from './composables/useGuestRestriction';
import { useSessionStore } from './composables/useSessionStore';
import type { ConsoleView } from './types/session';
import {
  LEGACY_STORAGE_KEY,
  STORAGE_KEY,
  STORAGE_SCHEMA_VERSION,
  readBootstrap,
} from './utils/storage';

const bootstrap = readBootstrap();

// ── 应用级 UI 偏好（本地持久化） ──────────────────────────────
const darkMode = ref(Boolean(bootstrap.darkMode));
const activeView = ref<ConsoleView>(bootstrap.activeView === 'evaluation' ? 'evaluation' : 'chat');
const settingsOpen = ref(false);
const sessionSearch = ref((bootstrap.sessionSearch as string | undefined) ?? '');
const workspaceFilter = ref((bootstrap.workspaceFilter as string | undefined) ?? 'all');
const showArchivedSessions = ref(Boolean(bootstrap.showArchivedSessions));
const sidebarOpen = ref(typeof window !== 'undefined' ? window.innerWidth > 900 : true);
const isMobileViewport = () => typeof window !== 'undefined' && window.innerWidth <= 900;
const isMobile = ref(isMobileViewport());
const workspaceDraft = ref('');
const hydrating = ref(true);
const evalSelectedDatasetId = ref((bootstrap.evalSelectedDatasetId as string | undefined) ?? '');

function persistState(): void {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      schemaVersion: STORAGE_SCHEMA_VERSION,
      darkMode: darkMode.value,
      activeView: activeView.value,
      apiKey: apiKeyInput.value,
      tenantId: tenantInput.value,
      token: token.value,
      refreshToken: refreshToken.value,
      evalSelectedDatasetId: evalSelectedDatasetId.value,
      activeSessionId: activeSessionId.value,
      sessionSearch: sessionSearch.value,
      workspaceFilter: workspaceFilter.value,
      showArchivedSessions: showArchivedSessions.value,
      sessions: sessions.value,
    }),
  );
}

// ── 模块装配 ────────────────────────────────────────────────
const authStore = useAuth({ bootstrap, persistState });
const {
  apiKeyInput,
  tenantInput,
  token,
  refreshToken,
  authLoading,
  refreshing,
  costSummary,
  guestMode,
  canUseRemoteSync,
  authContext,
  refreshCostSummary,
  ensureCredential,
  bindCloudSync,
  handleLogin,
  handleRefresh,
  clearAuth,
} = authStore;

const messageListRef = ref<InstanceType<typeof MessageList> | null>(null);
const evalPanelRef = ref<InstanceType<typeof EvaluationPanel> | null>(null);
const memoryHintRef = ref<InstanceType<typeof MemoryHintBar> | null>(null);

async function scrollToBottom(force = false): Promise<void> {
  await messageListRef.value?.scrollToBottom(force);
}

function resetMessageHeights(): void {
  messageListRef.value?.resetHeights();
}

const store = useSessionStore({
  bootstrap,
  persistState,
  auth: authStore,
  sessionSearch,
  workspaceFilter,
  showArchivedSessions,
  sidebarOpen,
  ui: { isMobileViewport, resetMessageHeights, scrollToBottom },
});

const {
  sessions,
  activeSessionId,
  activeSession,
  activeBranch,
  modelProfile,
  streaming,
  messages,
  traceSteps,
  prompt,
  editingMessageId,
  editingMessageDraft,
  cloudSyncing,
  sessionCount,
  workspaceOptions,
  activeWorkspaceId,
  filteredSessions,
  branchTreeItems,
  syncCurrentSessionBranch,
  loadSession,
  switchSession,
  createAndSwitchSession,
  removeSession,
  toggleSessionPin,
  toggleSessionArchive,
  switchBranch,
  forkFromCurrent,
  compareWithParent,
  mergeIntoParent,
  loadSessionsFromCloud,
  syncActiveSessionToCloud,
} = store;

const {
  sending,
  isStreamingResponse,
  streamStatusLabel,
  streamStatusTagType,
  streamStatusDetail,
  isEmptyConversation,
  traceDurationMs,
  expandedCitationCards,
  expandedTraceOpen,
  answerFeedbackMap,
  answerFeedbackLoading,
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
} = useChatEngine({ persistState, auth: authStore, store, ui: { scrollToBottom } });

bindCloudSync(() => loadSessionsFromCloud());

// ── 401 统一恢复（api/client.ts request 层触发）：静默刷新 → 失败清理登录态并引导登录 ──
configureAuthRecovery({
  getAuth: () => ({ token: token.value, refreshToken: refreshToken.value }),
  applyAuth: (auth) => {
    token.value = auth.token ?? token.value;
    refreshToken.value = auth.refreshToken ?? refreshToken.value;
    if (auth.tenantId) {
      tenantInput.value = auth.tenantId;
    }
    persistState();
  },
  onAuthExpired: () => {
    // 先判游客再清 token：guestMode 从 token 派生，清空后恒为 false
    const wasGuest = guestMode.value;
    token.value = '';
    refreshToken.value = '';
    persistState();
    if (wasGuest) {
      // 游客令牌过期：走既有游客通道静默重建，不打扰用户
      void ensureCredential();
      return;
    }
    settingsOpen.value = true; // 登录表单在设置弹窗中
    ElMessage.warning('登录状态已失效，请重新登录');
  },
});

// ── 游客 403 功能受限引导卡片：登录入口指向设置弹窗；登录成功（脱离游客态）后自动收起 ──
bindRestrictionLoginAction(() => {
  settingsOpen.value = true;
});

watch(guestMode, (isGuest) => {
  if (!isGuest) {
    dismissGuestRestriction();
  }
});

function updateViewport(): void {
  isMobile.value = isMobileViewport();
}

function activateView(view: ConsoleView): void {
  activeView.value = view;
  persistState();
  if (view === 'evaluation') {
    void evalPanelRef.value?.maybeLoadDatasets();
  }
}

function createWorkspace(): void {
  const value = workspaceDraft.value.trim().toLowerCase();
  if (!value) {
    return;
  }

  activeWorkspaceId.value = value;
  workspaceFilter.value = value;
  workspaceDraft.value = '';
  persistState();
}

/** 逃生门：本地状态异常（转圈/白屏/数据错乱）时一键清空重来。 */
function resetLocalState(): void {
  if (
    !window.confirm(
      '确定清除本地缓存并重新加载？当前设备的未同步会话将丢失（已同步云端的不受影响）。',
    )
  ) {
    return;
  }
  try {
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(LEGACY_STORAGE_KEY);
  } catch {
    // ignore
  }
  window.location.reload();
}

watch(
  darkMode,
  () => {
    document.documentElement.classList.toggle('dark', darkMode.value);
    persistState();
  },
  { immediate: true },
);

watch([modelProfile, streaming, workspaceFilter, showArchivedSessions, sessionSearch], () => {
  syncCurrentSessionBranch();
  persistState();
});

let syncPersistTimer: number | undefined;
let lastSyncPersistAt = 0;

/** 流式期间 messages 高频变化：sync+localStorage 全量序列化必须节流（400ms 尾随），否则手机端主线程被打满。 */
function scheduleSyncPersist(): void {
  const now = Date.now();
  if (now - lastSyncPersistAt >= 400) {
    lastSyncPersistAt = now;
    syncCurrentSessionBranch();
    persistState();
    return;
  }
  if (syncPersistTimer !== undefined) {
    return;
  }
  syncPersistTimer = window.setTimeout(() => {
    syncPersistTimer = undefined;
    lastSyncPersistAt = Date.now();
    syncCurrentSessionBranch();
    persistState();
  }, 400);
}

watch(
  [messages, traceSteps],
  () => {
    scheduleSyncPersist();
  },
  { deep: true },
);

onMounted(() => {
  // 先确保证据就绪（游客/已存 Key），再发起会话与评测等带鉴权的初始请求，避免开机 401
  void (async () => {
    if (!token.value) {
      await ensureCredential();
    }
    loadSession(activeSessionId.value);
    // 游客无 cost/memory 权限，跳过对应初始请求避免 401
    if (!guestMode.value) {
      memoryHintRef.value?.load();
      void refreshCostSummary();
    }
  })();
  updateViewport();

  window.addEventListener('resize', updateViewport);
  window.setTimeout(() => {
    hydrating.value = false;
    void scrollToBottom(true);
  }, 220);

  if (activeView.value === 'evaluation') {
    void evalPanelRef.value?.maybeLoadDatasets();
  }

  void scrollToBottom(true);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport);
});
</script>

<style scoped src="./styles/app-shell.css"></style>
