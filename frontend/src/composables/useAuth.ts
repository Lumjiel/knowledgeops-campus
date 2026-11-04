/**
 * 鉴权与租户成本：token / API Key 状态、游客模式判定、凭证恢复、登录刷新与成本摘要。
 * 从 App.vue 抽出；云端会话拉取通过 bindCloudSync 注入，避免与 useSessionStore 循环依赖。
 */
import { ElMessage } from 'element-plus';
import { computed, ref, watch } from 'vue';
import {
  exchangeApiKey,
  exchangeGuestToken,
  getTenantCostSummary,
  refreshJwt,
} from '../api/client';
import type { AuthContext, TenantCostSummary } from '../types/react';
import { toUserMessage } from '../utils/errors';

interface UseAuthOptions {
  bootstrap: Record<string, unknown>;
  persistState: () => void;
}

export function useAuth(options: UseAuthOptions) {
  const { bootstrap, persistState } = options;

  const apiKeyInput = ref((bootstrap.apiKey as string | undefined) ?? '');
  const tenantInput = ref((bootstrap.tenantId as string | undefined) ?? '');
  const token = ref((bootstrap.token as string | undefined) ?? '');
  const refreshToken = ref((bootstrap.refreshToken as string | undefined) ?? '');
  const authLoading = ref(false);
  const refreshing = ref(false);
  const costSummary = ref<TenantCostSummary | null>(null);

  /** 游客模式：从 token 的 subject 派生（guest-* 前缀），刷新后依然生效。 */
  const guestMode = computed(() => {
    const t = token.value || '';
    if (!t) return false;
    try {
      const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return String(payload.sub || '').startsWith('guest-');
    } catch {
      return false;
    }
  });

  const canUseRemoteSync = computed(() => Boolean(token.value || apiKeyInput.value.trim()));

  function authContext(): AuthContext {
    return {
      token: token.value || undefined,
      apiKey: apiKeyInput.value || undefined,
      tenantId: tenantInput.value || undefined,
    };
  }

  async function refreshCostSummary(): Promise<void> {
    if (!canUseRemoteSync.value) {
      costSummary.value = null;
      return;
    }
    try {
      costSummary.value = await getTenantCostSummary(authContext());
    } catch {
      // Keep UI usable even when cost endpoint is unavailable.
    }
  }

  /** 401 后的凭证恢复：优先用已保存的 API Key 重换 JWT，失败则降级游客模式。 */
  async function ensureCredential(): Promise<boolean> {
    token.value = '';
    if (apiKeyInput.value.trim()) {
      try {
        const auth = await exchangeApiKey(
          apiKeyInput.value.trim(),
          tenantInput.value.trim() || undefined,
        );
        token.value = auth.token ?? '';
        persistState();
        if (token.value) {
          return true;
        }
      } catch {
        // 落入游客通道
      }
    }
    try {
      const auth = await exchangeGuestToken(tenantInput.value.trim() || undefined);
      token.value = auth.token ?? '';
      persistState();
      return Boolean(token.value);
    } catch {
      return false;
    }
  }

  let cloudLoader: (() => Promise<void>) | null = null;

  /** handleLogin 成功后拉取云端会话；由 App 在会话仓就绪后注入。 */
  function bindCloudSync(loader: () => Promise<void>): void {
    cloudLoader = loader;
  }

  async function handleLogin(): Promise<void> {
    if (!apiKeyInput.value.trim()) {
      ElMessage.warning('请先输入 API Key');
      return;
    }

    authLoading.value = true;
    try {
      const auth = await exchangeApiKey(
        apiKeyInput.value.trim(),
        tenantInput.value.trim() || undefined,
      );
      token.value = auth.token ?? '';
      refreshToken.value = auth.refreshToken ?? '';
      if (auth.tenantId) {
        tenantInput.value = auth.tenantId;
      }
      ElMessage.success('JWT 获取成功');
      persistState();
      if (cloudLoader) {
        await cloudLoader();
      }
    } catch (error) {
      const message = toUserMessage(error, '登录失败，请检查 API Key 是否正确');
      ElMessage.error(message);
    } finally {
      authLoading.value = false;
    }
  }

  async function handleRefresh(): Promise<void> {
    if (!refreshToken.value) {
      ElMessage.warning('当前没有 refresh token');
      return;
    }

    refreshing.value = true;
    try {
      const auth = await refreshJwt(refreshToken.value);
      token.value = auth.token ?? token.value;
      refreshToken.value = auth.refreshToken ?? refreshToken.value;
      if (auth.tenantId) {
        tenantInput.value = auth.tenantId;
      }
      ElMessage.success('令牌已刷新');
      persistState();
      await refreshCostSummary();
    } catch (error) {
      const message = toUserMessage(error, '令牌刷新失败，请重新登录');
      ElMessage.error(message);
    } finally {
      refreshing.value = false;
    }
  }

  function clearAuth(): void {
    token.value = '';
    refreshToken.value = '';
    costSummary.value = null;
    ElMessage.success('鉴权状态已清空');
    persistState();
  }

  watch([apiKeyInput, tenantInput, token, refreshToken], () => {
    persistState();
    if (canUseRemoteSync.value) {
      void refreshCostSummary();
    } else {
      costSummary.value = null;
    }
  });

  return {
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
  };
}

export type AuthStore = ReturnType<typeof useAuth>;
