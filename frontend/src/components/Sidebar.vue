<template>
  <aside class="sidebar">
    <div class="brand-block">
      <h1>有章</h1>
      <p class="brand-sub">有章可循 · 有据可查</p>
    </div>

    <button class="new-chat-btn" type="button" @click="emit('create-session')">
      ＋ 新建会话
    </button>

    <section class="session-tools">
      <el-input v-model="searchModel" size="small" placeholder="搜索会话标题或 ID" clearable />
    </section>

    <section class="session-panel">
      <div class="section-head">
        <p class="section-label">会话</p>
        <span class="section-meta">{{ sessions.length }}/{{ sessionCount }}</span>
      </div>
      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.id"
          class="session-item"
          :class="{ active: session.id === activeSessionId }"
          role="button"
          tabindex="0"
          @click="emit('switch-session', session.id)"
          @keydown.enter.prevent="emit('switch-session', session.id)"
        >
          <div class="session-content">
            <div class="session-title-row">
              <p class="session-title">{{ session.title }}</p>
              <el-tag v-if="session.pinned" size="small" type="success" effect="plain"
                >置顶</el-tag
              >
              <el-tag v-if="session.archived" size="small" type="info" effect="plain">归档</el-tag>
            </div>
            <p class="session-meta-row">
              {{ session.workspaceId }} · {{ formatTime(session.updatedAt) }} ·
              {{ shortId(session.id) }}
            </p>
          </div>
          <div class="session-actions">
            <button type="button" @click.stop="emit('toggle-pin', session.id)">
              {{ session.pinned ? '取消置顶' : '置顶' }}
            </button>
            <button type="button" @click.stop="emit('toggle-archive', session.id)">
              {{ session.archived ? '取消归档' : '归档' }}
            </button>
            <button type="button" class="danger" @click.stop="emit('remove-session', session.id)">
              删除
            </button>
          </div>
        </div>
        <div v-if="sessions.length === 0" class="session-empty">没有匹配会话</div>
      </div>
    </section>

    <el-collapse class="branch-collapse">
      <el-collapse-item name="branch">
        <template #title>
          <span class="branch-collapse-title"
            >分支树（{{ activeSession?.branches.length ?? 0 }} 条）</span
          >
        </template>
        <section class="branch-panel">
          <div class="branch-head-actions">
            <el-button size="small" @click="emit('fork-from-current')">从当前分叉</el-button>
            <el-button
              size="small"
              :disabled="!activeBranch?.parentBranchId"
              @click="emit('compare-with-parent')"
            >
              对比父分支
            </el-button>
            <el-button
              size="small"
              :disabled="!activeBranch?.parentBranchId"
              @click="emit('merge-into-parent')"
            >
              合并到父分支
            </el-button>
          </div>
          <div class="branch-list">
            <div
              v-for="node in branchTreeItems"
              :key="node.branch.id"
              class="branch-item"
              :class="{ active: node.branch.id === activeBranch?.id }"
              :style="{ paddingLeft: `${12 + node.depth * 14}px` }"
              role="button"
              tabindex="0"
              @click="emit('switch-branch', node.branch.id)"
              @keydown.enter.prevent="emit('switch-branch', node.branch.id)"
            >
              <span class="branch-line" :style="{ opacity: node.depth > 0 ? 1 : 0 }"></span>
              <div class="branch-content">
                <p>{{ node.branch.title }}</p>
                <small>{{ formatTime(node.branch.updatedAt) }}</small>
              </div>
            </div>
            <div v-if="branchTreeItems.length === 0" class="session-empty">暂无分支</div>
          </div>
        </section>
      </el-collapse-item>
    </el-collapse>

    <footer class="sidebar-foot">
      <button
        type="button"
        class="eval-entry"
        @click="emit('activate-view', view === 'chat' ? 'evaluation' : 'chat')"
      >
        {{ view === 'chat' ? '评测模式 · 数据看板' : '← 返回对话' }}
      </button>
    </footer>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { BranchTreeItem, ConsoleView, SessionBranch, SessionRecord } from '../types/session';

const props = defineProps<{
  /** 搜索关键字（v-model:search，持久化由父组件负责） */
  search: string;
  /** 过滤 + 排序后的会话列表 */
  sessions: SessionRecord[];
  /** 会话总数（含被过滤的），用于 x/y 计数展示 */
  sessionCount: number;
  activeSessionId: string;
  activeSession: SessionRecord | undefined;
  activeBranch: SessionBranch | undefined;
  branchTreeItems: BranchTreeItem[];
  /** 当前主区视图，决定底部入口按钮文案与切换方向 */
  view: ConsoleView;
}>();

const emit = defineEmits<{
  (e: 'update:search', value: string): void;
  (e: 'create-session'): void;
  (e: 'switch-session', sessionId: string): void;
  (e: 'toggle-pin', sessionId: string): void;
  (e: 'toggle-archive', sessionId: string): void;
  (e: 'remove-session', sessionId: string): void;
  (e: 'switch-branch', branchId: string): void;
  (e: 'fork-from-current'): void;
  (e: 'compare-with-parent'): void;
  (e: 'merge-into-parent'): void;
  (e: 'activate-view', view: ConsoleView): void;
}>();

const searchModel = computed({
  get: () => props.search,
  set: (value: string) => emit('update:search', value),
});

function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function shortId(id: string): string {
  return id.slice(0, 10);
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-right: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  backdrop-filter: blur(12px);
}

.brand-block {
  padding: 4px 2px;
}

.eyebrow {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

h1 {
  margin: 8px 0 0;
  font-size: 25px;
  line-height: 1.1;
  letter-spacing: -0.02em;
}

.brand-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--ui-muted);
}

.new-chat-btn {
  border: 1px solid rgba(14, 116, 144, 0.35);
  background: linear-gradient(150deg, rgba(14, 116, 144, 0.2), rgba(15, 118, 110, 0.14));
  color: var(--ui-text);
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}

.new-chat-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(14, 116, 144, 0.18);
}

.session-tools {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

/* .section-label 同时被评测页使用，App.vue 内保留同名校验样式 */
.section-label {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

.section-meta {
  font-size: 12px;
  color: var(--ui-muted);
}

.session-panel,
.branch-panel {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 84%, transparent);
}

.session-list,
.branch-list {
  max-height: 214px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-item {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  background: color-mix(in oklab, var(--ui-card) 76%, transparent);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color 160ms ease;
}

.session-item:hover,
.session-item.active {
  border-color: rgba(14, 116, 144, 0.45);
}

.session-content {
  min-width: 0;
}

.session-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.session-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta-row {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ui-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.session-actions button,
.branch-head-actions button {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 3px 8px;
  font-size: 11px;
  cursor: pointer;
}

.session-actions .danger {
  color: #dc2626;
}

/* .session-empty 同时被评测页「暂无评测集」使用，App.vue 内保留同名样式 */
.session-empty {
  font-size: 12px;
  color: var(--ui-muted);
  padding: 6px;
}

.branch-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.branch-item {
  position: relative;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: color-mix(in oklab, var(--ui-card) 80%, transparent);
  cursor: pointer;
}

.branch-item.active {
  border-color: rgba(14, 116, 144, 0.45);
}

.branch-line {
  width: 10px;
  height: 1px;
  background: var(--ui-muted);
}

.branch-content {
  min-width: 0;
}

.branch-content p,
.branch-content small {
  margin: 0;
}

.branch-content p {
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-content small {
  color: var(--ui-muted);
}

/* ════════════════════════════════════════════════
   有章 · 学术风覆盖层（自 App.vue 随模板迁入，保持原级联顺序）
   ════════════════════════════════════════════════ */

/* ── 侧栏 ── */
.sidebar {
  gap: 10px;
  padding: 16px 14px 10px;
  background: var(--ui-surface);
  border-right: 1px solid var(--ui-border-soft);
  backdrop-filter: none;
}

.brand-block h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ui-text);
}

.brand-block .brand-sub {
  margin: 3px 0 0;
  font-size: 10.5px;
  line-height: 1.4;
  color: var(--ui-muted);
}

.new-chat-btn {
  height: 38px;
  border: none;
  border-radius: 12px;
  background: var(--ui-accent);
  color: #fff;
  font-size: 13.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: filter 0.15s ease;
}

.new-chat-btn:hover {
  filter: brightness(1.08);
}

.session-tools .el-input {
  --el-input-bg-color: var(--ui-paper);
}

.session-panel {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.session-item {
  border: none;
  border-radius: 10px;
  background: transparent;
  transition: background 0.12s ease;
}

.session-item:hover {
  background: var(--ui-paper);
}

.session-item.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

.session-item.active .session-title {
  color: var(--ui-accent);
  font-weight: 500;
}

.session-title {
  font-size: 13.5px;
}

.session-meta-row {
  font-size: 11px;
  color: var(--ui-muted);
}

.session-actions button {
  border: none;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 6px;
}

.session-actions button:hover {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

.session-actions button.danger:hover {
  color: var(--ui-danger);
}

/* 分支折叠面板 */
.branch-collapse {
  border-top: 1px solid var(--ui-border-soft);
  --el-collapse-border-color: transparent;
}

.branch-collapse .el-collapse-item__header {
  background: transparent;
  border-bottom: none;
  font-size: 12px;
  color: var(--ui-muted);
  height: 36px;
}

.branch-collapse-title {
  font-size: 12px;
  color: var(--ui-muted);
}

.branch-panel {
  padding: 0 2px 8px;
}

.branch-head-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.branch-item {
  border: none;
  border-radius: 8px;
}

.branch-item.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

/* 侧栏底部评测入口 */
.sidebar-foot {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--ui-border-soft);
}

.eval-entry {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition:
    color 0.12s ease,
    background 0.12s ease;
}

.eval-entry:hover {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

/* ════════ 响应式（原 App.vue 中作用于侧栏内部元素的部分） ════════ */
@media (max-width: 980px) {
  .session-list,
  .branch-list {
    max-height: 170px;
  }
}

@media (max-width: 900px) {
  .brand-sub {
    display: none;
  }

  .brand-block h1 {
    font-size: 14px;
  }
}
</style>
