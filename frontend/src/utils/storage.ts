/**
 * 本地持久化：存储键、schema 版本与启动状态读取。
 * 从 App.vue 抽出，供 persistState / resetLocalState 与各 composable 共用。
 */

export const STORAGE_KEY = 'knowledgeops-agent-react-console-v2';
export const LEGACY_STORAGE_KEY = 'knowledgeops-agent-react-console';

/**
 * 本地存储 schema 版本：代码结构变更时 +1，启动时版本不符的旧状态会被整体丢弃，
 * 避免旧版本写入的状态与新代码不兼容导致启动挂起（2026-11-02 转圈事故的根治）。
 */
export const STORAGE_SCHEMA_VERSION = 3;

export function safeParse(raw: string | null): Record<string, unknown> {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function readBootstrap(): Record<string, unknown> {
  const stored = safeParse(localStorage.getItem(STORAGE_KEY));
  if (Object.keys(stored).length === 0) {
    return safeParse(localStorage.getItem(LEGACY_STORAGE_KEY));
  }
  if (stored.schemaVersion !== STORAGE_SCHEMA_VERSION) {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // 隐私模式等场景下 removeItem 可能失败，忽略即可（本次会话用全新状态）
    }
    return {};
  }
  return stored;
}
