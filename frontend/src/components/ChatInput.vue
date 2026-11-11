<template>
  <footer class="composer-shell">
    <div class="composer">
      <div class="composer-main">
        <button
          v-if="!guestMode"
          type="button"
          class="composer-upload"
          aria-label="上传 PDF"
          data-tooltip="上传 PDF 到本会话知识库"
          @click="triggerPdfUpload"
        >
          ＋
        </button>
        <el-input
          v-model="promptModel"
          class="composer-input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 5 }"
          resize="none"
          placeholder="输入你的问题…"
          @keydown.enter.exact.prevent="emit('send')"
        />
        <el-button
          v-if="sending"
          class="composer-send"
          :disabled="!sending"
          @click="emit('stop')"
          >停止</el-button
        >
        <el-button
          v-else
          class="composer-send"
          type="primary"
          :disabled="!prompt.trim()"
          @click="emit('send')"
          >发送</el-button
        >
      </div>
      <input
        ref="pdfInputRef"
        type="file"
        accept="application/pdf"
        style="display: none"
        @change="onPdfFileChange"
      />
    </div>
    <p class="composer-disclaimer">内容由 AI 生成，请以学校最新官方通知为准</p>
  </footer>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { computed, ref } from 'vue';
import { uploadPdf } from '../api/client';
import { toUserMessage } from '../utils/errors';

const props = defineProps<{
  /** 输入草稿由父组件持有：切换会话/分支时需要保留或清空 */
  prompt: string;
  sending: boolean;
  guestMode: boolean;
  isEmptyConversation: boolean;
  /** PDF 上传目标会话（uploadPdf 以 activeSessionId 作为 chatId） */
  sessionId: string;
}>();

const emit = defineEmits<{
  (e: 'update:prompt', value: string): void;
  (e: 'send'): void;
  (e: 'stop'): void;
}>();

const promptModel = computed({
  get: () => props.prompt,
  set: (value: string) => {
    emit('update:prompt', value);
  },
});


const pdfInputRef = ref<HTMLInputElement | null>(null);
const pdfUploading = ref(false);

function triggerPdfUpload(): void {
  pdfInputRef.value?.click();
}

async function onPdfFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) {
    return;
  }
  pdfUploading.value = true;
  try {
    await uploadPdf(props.sessionId, file);
    ElMessage.success('PDF 已提交解析，入库后即可就本文档提问');
  } catch (error) {
    ElMessage.error(toUserMessage(error, '文件上传失败，请稍后重试'));
  } finally {
    pdfUploading.value = false;
  }
}
</script>

<style scoped>
.composer-shell {
  position: sticky;
  bottom: 0;
  z-index: 6;
  padding: 0 16px 16px;
  background: linear-gradient(
    180deg,
    transparent 0%,
    color-mix(in oklab, var(--ui-card) 65%, transparent) 32%,
    color-mix(in oklab, var(--ui-card) 92%, transparent) 100%
  );
}

.composer {
  max-width: 930px;
  margin: 0 auto;
  border: 1px solid var(--ui-border);
  border-radius: 16px;
  background: color-mix(in oklab, var(--ui-card) 92%, transparent);
  backdrop-filter: blur(12px);
  padding: 10px;
}

.composer-footer {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.composer-actions {
  display: flex;
  gap: 8px;
}

:deep(.composer .el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  font-size: 15px;
  line-height: 1.58;
  color: var(--ui-text);
}

:deep(.composer .el-textarea__inner:focus) {
  box-shadow: none;
}

@media (max-width: 980px) {
  .composer-shell {
    position: static;
  }
}

@media (max-width: 680px) {
  .composer-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

/* ════════════════════════════════════════════════
   有章 · 学术风覆盖层（位于文件末尾以获得优先级）
   ════════════════════════════════════════════════ */

/* ── 输入区 ── */
.composer-shell {
  max-width: 930px;
  margin: 0 auto;
  width: 100%;
  padding: 8px 20px 14px;
  background: transparent;
  border-top: none;
}

.composer {
  border: 1px solid var(--ui-border);
  border-radius: 14px;
  background: var(--ui-surface);
  box-shadow: var(--ui-shadow);
  padding: 8px 10px 6px;
}

.composer-main {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.composer-main :deep(.composer-input) {
  flex: 1;
}

.composer-upload {
  width: 30px;
  height: 30px;
  flex: none;
  border: 1px dashed var(--ui-border);
  border-radius: 9px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 15px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.composer-upload:hover {
  color: var(--ui-accent);
  border-color: var(--ui-accent-line);
  background: var(--ui-accent-soft);
}

.composer-send {
  flex: none;
}

.composer-stop {
  display: flex;
  justify-content: center;
  padding-bottom: 2px;
}

.composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.composer-disclaimer {
  margin: 6px 0 0;
  text-align: center;
  font-size: 10.5px;
  color: var(--ui-muted);
  opacity: 0.8;
}

/* ── 移动端（≤900px）：输入区收紧 ── */
@media (max-width: 900px) {
  .composer-shell {
    padding: 4px 8px 8px;
  }

  .composer {
    padding: 6px 8px 5px;
    border-radius: 11px;
  }

  .composer-disclaimer {
    display: none;
  }

  .composer-send {
    padding: 0 12px;
    height: 30px;
    min-height: 30px;
  }

  .composer-upload {
    width: 28px;
    height: 28px;
  }
}
</style>

/* ── 移动端：输入区收紧（≤680px） ── */
@media (max-width: 680px) {
  .composer-shell {
    padding: 0 10px 10px;
  }

  .composer {
    padding: 8px;
    border-radius: 12px;
  }
}
