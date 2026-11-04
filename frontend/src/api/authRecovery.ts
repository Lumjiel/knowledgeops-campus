/**
 * 401 统一恢复：先静默刷新会话，失败则清理本地登录状态并通知引导到登录页。
 *
 * 由 api/client.ts 的 request() 在收到 401 响应时调用（鉴权端点自身除外）。
 * 鉴权状态不直接依赖 Vue：运行期钩子由 App.vue 装配时通过 configureAuthRecovery
 * 注入（与 bindCloudSync 同一套思路，避免 client ↔ useAuth 循环依赖）；
 * 钩子未注册的极端时序下回退为直读/直写 localStorage。
 *
 * 与本地存储版本守卫（utils/storage.ts）的兼容约定：
 * - 读取一律走 readBootstrap()，版本不符的旧数据只会得到空对象；
 * - 回写前校验 schemaVersion，版本不符不写回，交给启动时 readBootstrap 整体丢弃，
 *   绝不把半新半旧的状态固化为当前版本（2026-11-02 转圈事故的教训）。
 */
import type { AuthContext, AuthTokenResponse } from '../types/react';
import { STORAGE_KEY, STORAGE_SCHEMA_VERSION, readBootstrap } from '../utils/storage';

export interface AuthRecoveryHooks {
  /** 当前鉴权快照，用于取 refreshToken 与刷新成功后的重试 token。 */
  getAuth?: () => Partial<AuthContext> & { refreshToken?: string };
  /** 静默刷新成功后回填新凭证（实现方需触发本地持久化）。 */
  applyAuth?: (auth: AuthTokenResponse) => void;
  /** 静默刷新失败后的收尾：清理登录状态并引导到登录页。 */
  onAuthExpired?: () => void;
}

let hooks: AuthRecoveryHooks = {};
/** 并发 401 共享同一次刷新，避免多请求同时打 /auth/refresh。 */
let refreshInFlight: Promise<boolean> | null = null;
/** 引导登录的提示节流：同一批并发 401 只收尾一次。 */
let lastExpiredHandledAt = 0;

const EXPIRED_HANDLE_INTERVAL_MS = 3000;

/** 装配期注册钩子（App.vue 持有登录 UI 与持久化，因此由它注册）。 */
export function configureAuthRecovery(next: AuthRecoveryHooks): void {
  hooks = { ...hooks, ...next };
}

/**
 * 401 入口：静默刷新成功则换新 token 重试一次原请求并返回新响应；
 * 失败则清理登录状态、引导到登录页，并把原 401 响应原样交回，
 * 让各调用方既有的错误处理（FriendlyError → 友好文案）正常收尾，不会悬挂转圈。
 */
export async function recoverFrom401(
  url: string,
  init: RequestInit | undefined,
  response: Response,
): Promise<Response> {
  const refreshed = await trySilentRefresh();
  if (refreshed) {
    const token = hooks.getAuth?.().token ?? readStoredAuth().token;
    if (token) {
      const headers = new Headers(init?.headers);
      // 刷新后的 token 是权威凭证：移除可能已失效的 X-API-Key，避免后端校验冲突
      headers.delete('X-API-Key');
      headers.set('Authorization', `Bearer ${token}`);
      const retry = await fetch(url, { ...init, headers });
      if (retry.status === 401) {
        // 刷新后的 token 仍被拒：按过期收尾，避免静默失败
        handleAuthExpired();
      }
      return retry;
    }
  }
  handleAuthExpired();
  return response;
}

async function trySilentRefresh(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = doSilentRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doSilentRefresh(): Promise<boolean> {
  const refreshToken = hooks.getAuth?.().refreshToken ?? readStoredAuth().refreshToken;
  if (!refreshToken) {
    return false;
  }
  try {
    const auth = await rawRefreshJwt(refreshToken);
    if (hooks.applyAuth) {
      hooks.applyAuth(auth);
    } else {
      writeStoredAuthFields(auth);
    }
    return Boolean(auth.token);
  } catch {
    return false;
  }
}

function handleAuthExpired(): void {
  const now = Date.now();
  if (now - lastExpiredHandledAt < EXPIRED_HANDLE_INTERVAL_MS) {
    return;
  }
  lastExpiredHandledAt = now;
  if (hooks.onAuthExpired) {
    hooks.onAuthExpired();
    return;
  }
  clearStoredAuthFields();
}

function readStoredAuth(): { token?: string; refreshToken?: string } {
  const stored = readBootstrap();
  return {
    token: typeof stored.token === 'string' ? stored.token : undefined,
    refreshToken: typeof stored.refreshToken === 'string' ? stored.refreshToken : undefined,
  };
}

function writeStoredAuthFields(auth: AuthTokenResponse): void {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    const stored = JSON.parse(raw) as Record<string, unknown>;
    if (stored.schemaVersion !== STORAGE_SCHEMA_VERSION) {
      // 版本不符不写回：启动时 readBootstrap 会整体丢弃，这里不能固化成"当前版本"
      return;
    }
    if (auth.token) {
      stored.token = auth.token;
    }
    if (auth.refreshToken) {
      stored.refreshToken = auth.refreshToken;
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  } catch {
    // 隐私模式等场景下写入可能失败，忽略即可
  }
}

function clearStoredAuthFields(): void {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return;
    }
    const stored = JSON.parse(raw) as Record<string, unknown>;
    if (stored.schemaVersion !== STORAGE_SCHEMA_VERSION) {
      return;
    }
    delete stored.token;
    delete stored.refreshToken;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));
  } catch {
    // ignore
  }
}

/**
 * 直接调 /auth/refresh（不走 client.ts 的 request 封装，避免恢复逻辑自我递归；
 * 也不复用 client.refreshJwt —— 那会引入 client ↔ authRecovery 循环依赖）。
 */
async function rawRefreshJwt(refreshToken: string): Promise<AuthTokenResponse> {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
  const response = await fetch(`${base}/auth/refresh`, {
    credentials: 'include',
    method: 'POST',
    headers: {
      'X-Refresh-Token': refreshToken,
    },
  });
  const text = await response.text();
  let payload: AuthTokenResponse | null = null;
  try {
    payload = text ? (JSON.parse(text) as AuthTokenResponse) : null;
  } catch {
    payload = null;
  }
  if (!response.ok || !payload || payload.ok !== 1) {
    throw new Error(payload?.msg ?? 'refresh token failed');
  }
  return payload;
}
