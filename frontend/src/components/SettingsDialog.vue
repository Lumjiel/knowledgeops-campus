<template>
  <el-dialog
    :model-value="open"
    title="设置"
    :width="isMobile ? '94%' : '480px'"
    class="settings-dialog"
    @update:model-value="emit('update:open', $event)"
  >
    <div class="settings-section">
      <p class="settings-label">鉴权与模型</p>
      <el-form label-position="top" size="small">
        <el-form-item label="API Key">
          <el-input
            v-model="apiKeyModel"
            placeholder="输入 API Key（生产建议短时使用）"
            show-password
            type="password"
          />
        </el-form-item>
        <el-form-item label="Tenant (可选)">
          <el-input v-model="tenantModel" placeholder="public" />
        </el-form-item>
        <el-form-item label="Model Profile">
          <el-segmented
            v-model="modelProfileModel"
            :options="[
              'economy',
              'balanced',
              'quality',
              'ab_auto',
              'quality_first',
              'cost_first',
            ]"
            class="full-width"
          />
        </el-form-item>
        <el-form-item label="响应模式">
          <el-switch v-model="streamingModel" inline-prompt active-text="SSE" inactive-text="JSON" />
        </el-form-item>
      </el-form>
      <div class="auth-buttons">
        <el-button type="primary" :loading="authLoading" @click="emit('login')">换取 JWT</el-button>
        <el-button :disabled="!refreshToken" :loading="refreshing" @click="emit('refresh-auth')"
          >刷新</el-button
        >
        <el-button @click="emit('clear-auth')">清空</el-button>
      </div>
    </div>

    <div class="settings-section">
      <p class="settings-label">工作区</p>
      <div class="workspace-manager">
        <el-select v-model="activeWorkspaceIdModel" size="small" class="workspace-select">
          <el-option
            v-for="workspace in workspaceOptions"
            :key="workspace"
            :label="workspace"
            :value="workspace"
          />
        </el-select>
        <el-input
          v-model="workspaceDraftModel"
          size="small"
          class="workspace-input"
          placeholder="新工作区"
          @keydown.enter.prevent="emit('create-workspace')"
        />
        <el-button size="small" @click="emit('create-workspace')">创建并切换</el-button>
      </div>
    </div>

    <div class="settings-section">
      <p class="settings-label">会话列表与同步</p>
      <div class="workspace-manager">
        <el-select v-model="workspaceFilterModel" size="small" class="tool-select">
          <el-option label="全部工作区" value="all" />
          <el-option
            v-for="workspace in workspaceOptions"
            :key="workspace"
            :label="workspace"
            :value="workspace"
          />
        </el-select>
        <el-switch
          v-model="showArchivedSessionsModel"
          size="small"
          inline-prompt
          active-text="含归档"
          inactive-text="隐藏归档"
        />
      </div>
      <div class="cloud-row">
        <el-button
          size="small"
          :loading="cloudSyncing"
          :disabled="!canUseRemoteSync"
          @click="emit('pull-cloud')"
          >云端拉取</el-button
        >
        <el-button
          size="small"
          type="primary"
          :loading="cloudSyncing"
          :disabled="!canUseRemoteSync"
          @click="emit('sync-cloud')"
          >保存当前会话到云端</el-button
        >
      </div>
    </div>
    <template #footer>
      <el-button size="small" type="danger" plain @click="emit('clear-conversation')"
        >清空当前会话</el-button
      >
      <el-button size="small" @click="emit('reset-local')">重置本地数据</el-button>
      <el-button size="small" type="primary" @click="emit('update:open', false)">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 设置弹窗：鉴权与模型、工作区管理、会话列表过滤与云端同步入口。
 * 从 App.vue 整块拆出；自身不持有业务状态，全部通过 v-model / 事件与 App 通讯。
 */
import { computed } from 'vue';

const props = defineProps<{
  open: boolean;
  isMobile: boolean;
  apiKey: string;
  tenant: string;
  modelProfile: string;
  streaming: boolean;
  refreshToken: string;
  workspaceOptions: string[];
  activeWorkspaceId: string;
  workspaceFilter: string;
  showArchivedSessions: boolean;
  workspaceDraft: string;
  authLoading: boolean;
  refreshing: boolean;
  cloudSyncing: boolean;
  canUseRemoteSync: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'update:apiKey', value: string): void;
  (e: 'update:tenant', value: string): void;
  (e: 'update:modelProfile', value: string): void;
  (e: 'update:streaming', value: boolean): void;
  (e: 'update:activeWorkspaceId', value: string): void;
  (e: 'update:workspaceFilter', value: string): void;
  (e: 'update:showArchivedSessions', value: boolean): void;
  (e: 'update:workspaceDraft', value: string): void;
  (e: 'login'): void;
  (e: 'refresh-auth'): void;
  (e: 'clear-auth'): void;
  (e: 'create-workspace'): void;
  (e: 'pull-cloud'): void;
  (e: 'sync-cloud'): void;
  (e: 'clear-conversation'): void;
  (e: 'reset-local'): void;
}>();

const apiKeyModel = computed({
  get: () => props.apiKey,
  set: (value: string) => emit('update:apiKey', value),
});

const tenantModel = computed({
  get: () => props.tenant,
  set: (value: string) => emit('update:tenant', value),
});

const modelProfileModel = computed({
  get: () => props.modelProfile,
  set: (value: string) => emit('update:modelProfile', value),
});

const streamingModel = computed({
  get: () => props.streaming,
  set: (value: boolean) => emit('update:streaming', value),
});

const activeWorkspaceIdModel = computed({
  get: () => props.activeWorkspaceId,
  set: (value: string) => emit('update:activeWorkspaceId', value),
});

const workspaceFilterModel = computed({
  get: () => props.workspaceFilter,
  set: (value: string) => emit('update:workspaceFilter', value),
});

const showArchivedSessionsModel = computed({
  get: () => props.showArchivedSessions,
  set: (value: boolean) => emit('update:showArchivedSessions', value),
});

const workspaceDraftModel = computed({
  get: () => props.workspaceDraft,
  set: (value: string) => emit('update:workspaceDraft', value),
});
</script>

<style scoped>
.settings-section {
  padding: 4px 0 12px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--ui-border-soft);
}

.settings-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
}

.settings-label {
  margin: 0 0 10px;
  font-weight: 600;
  font-size: 13px;
  color: var(--ui-text);
}

.workspace-manager {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.workspace-manager .workspace-select {
  flex: 1;
  min-width: 140px;
}

.workspace-manager .workspace-input {
  flex: 1;
  min-width: 120px;
}

.cloud-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.settings-dialog .cloud-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.auth-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.full-width {
  width: 100%;
}

.tool-select {
  min-width: 0;
}

.workspace-select {
  width: 130px;
}

.workspace-input {
  width: 130px;
}

@media (max-width: 680px) {
  .workspace-select,
  .workspace-input {
    width: 120px;
  }
}
</style>
