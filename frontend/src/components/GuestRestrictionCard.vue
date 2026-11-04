<template>
  <transition name="restriction-fade">
    <div
      v-if="restriction"
      class="restriction-overlay"
      @click.self="dismissGuestRestriction()"
    >
      <section class="restriction-card" role="alertdialog" aria-modal="true">
        <div class="restriction-icon" aria-hidden="true">🔒</div>
        <p class="restriction-kicker">游客模式 · 功能受限</p>
        <h3 class="restriction-title">{{ restriction.feature }}暂未开放</h3>
        <p class="restriction-desc">{{ restriction.detail }}</p>
        <ul class="restriction-list">
          <li>游客模式仅支持在线问答、会话管理与回答反馈</li>
          <li>登录后可解锁评测工作台、成本看板、长期记忆与知识库上传</li>
        </ul>
        <div class="restriction-actions">
          <el-button text @click="dismissGuestRestriction()">继续体验</el-button>
          <el-button type="primary" @click="openRestrictionLogin()">使用 API Key 登录</el-button>
        </div>
      </section>
    </div>
  </transition>
</template>

<script setup lang="ts">
/**
 * 游客功能受限引导卡片：替换裸 403 错误提示，给出「升级/登录」操作入口。
 * 状态与动作来自 composables/useGuestRestriction.ts 的模块级单例，
 * 本组件由 App.vue 挂载一次即可，无需传参。
 */
import { useGuestRestriction } from '../composables/useGuestRestriction';

const { restriction, dismissGuestRestriction, openRestrictionLogin } = useGuestRestriction();
</script>

<style scoped>
/* ── 游客受限引导：居中卡片 + 半透明遮罩，配色复用全局 --ui-* 变量 ── */
.restriction-overlay {
  position: fixed;
  inset: 0;
  z-index: 2200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(28, 43, 58, 0.35);
}

.restriction-card {
  width: min(420px, 100%);
  padding: 28px 28px 22px;
  text-align: center;
  background: var(--ui-card, rgba(255, 255, 255, 0.92));
  border: 1px solid var(--ui-border, rgba(28, 43, 58, 0.12));
  border-radius: var(--ui-radius, 10px);
  box-shadow: var(--ui-shadow, 0 4px 16px rgba(28, 43, 58, 0.08));
}

.restriction-icon {
  width: 52px;
  height: 52px;
  margin: 0 auto 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  border-radius: 50%;
  background: var(--ui-accent-soft, #e8f0f1);
}

.restriction-kicker {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.04em;
  color: var(--ui-muted, #5b6b7c);
}

.restriction-title {
  margin: 0 0 10px;
  font-size: 18px;
  color: var(--ui-text, #1c2b3a);
}

.restriction-desc {
  margin: 0 0 14px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--ui-text, #1c2b3a);
}

.restriction-list {
  margin: 0 0 20px;
  padding: 12px 16px;
  text-align: left;
  list-style: none;
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--ui-muted, #5b6b7c);
  background: var(--ui-panel, rgba(247, 245, 240, 0.85));
  border-radius: var(--ui-radius, 10px);
}

.restriction-list li::before {
  content: '·';
  margin-right: 6px;
  color: var(--ui-accent, #0f4c5c);
}

.restriction-actions {
  display: flex;
  justify-content: center;
  gap: 10px;
}

/* 进入/离开的淡入淡出，避免生硬弹出 */
.restriction-fade-enter-active,
.restriction-fade-leave-active {
  transition: opacity 0.18s ease;
}

.restriction-fade-enter-from,
.restriction-fade-leave-to {
  opacity: 0;
}
</style>
