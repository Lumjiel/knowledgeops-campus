<template>
  <div v-if="memoryHint" class="memory-hint">
    <span
      >我记得你是
      <template v-if="memoryHint.college">{{ memoryHint.college }}</template>
      <template v-if="memoryHint.college && memoryHint.grade"> · </template>
      <template v-if="memoryHint.grade">{{ memoryHint.grade }} 级</template>
    </span>
    <button type="button" class="memory-hint-clear" @click="clearMemoryHint">清除</button>
  </div>
</template>

<script setup lang="ts">
/**
 * A6 记忆提示条：数据来自 GET /api/memory，接口不可用或无槽位则不显示。
 * 从 App.vue 拆出；鉴权上下文由 App 传入，加载时机由 App 通过 expose 的 load() 控制
 * （游客模式不加载，避免 401）。
 */
import { onMounted, ref } from 'vue';
import type { AuthContext } from '../types/react';

interface MemoryHint {
  college?: string | null;
  grade?: number | null;
  memoryId?: string;
}

const props = defineProps<{
  authContext: () => AuthContext;
  /** 是否允许加载（游客模式为 false，避免 401）；App 在凭证就绪后也会主动调 load()。 */
  canLoad: boolean;
}>();

const memoryHint = ref<MemoryHint | null>(null);

function memoryApiBase(): string {
  return (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
}

function memoryAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const auth = props.authContext();
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`;
  } else if (auth.apiKey) {
    headers['X-API-Key'] = auth.apiKey;
  }
  return headers;
}

async function loadMemoryHint(): Promise<void> {
  try {
    const response = await fetch(`${memoryApiBase()}/memory`, {
      headers: memoryAuthHeaders(),
    });
    if (!response.ok) {
      memoryHint.value = null;
      return;
    }
    const data = (await response.json()) as {
      slots?: { college?: string; grade?: number };
      memoryId?: string;
    };
    if (data?.slots && (data.slots.college || data.slots.grade)) {
      memoryHint.value = {
        college: data.slots.college ?? null,
        grade: data.slots.grade ?? null,
        memoryId: data.memoryId ?? '',
      };
    } else {
      memoryHint.value = null;
    }
  } catch {
    memoryHint.value = null; // 后端不可达时静默隐藏，不报错
  }
}

async function clearMemoryHint(): Promise<void> {
  if (!memoryHint.value?.memoryId) {
    memoryHint.value = null;
    return;
  }
  try {
    await fetch(`${memoryApiBase()}/memory/${encodeURIComponent(memoryHint.value.memoryId)}`, {
      method: 'DELETE',
      headers: memoryAuthHeaders(),
    });
  } catch {
    // 删除失败静默，下一次加载以服务端为准
  }
  memoryHint.value = null;
}

defineExpose({
  load: loadMemoryHint,
});

// 视图切换到会话页导致本组件（重）挂载时自行加载；首次挂载时 App 会在凭证就绪后再调一次
onMounted(() => {
  if (props.canLoad) {
    void loadMemoryHint();
  }
});
</script>

<style scoped>
/* ── 记忆提示条（spec-A6：浅色小字，接口有数据才显示） ── */
.memory-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  font-size: 13px;
  color: var(--color-text-sub);
  background: var(--color-quote-bg);
  border-radius: var(--radius-chip);
}

.memory-hint-clear {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-accent);
  cursor: pointer;
}
</style>
