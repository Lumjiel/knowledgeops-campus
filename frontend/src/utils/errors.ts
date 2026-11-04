/**
 * 统一的用户可见错误文案模块。
 *
 * 网络错误、超时、权限不足、服务异常等提示文案全部集中在这里维护；
 * 页面代码一律通过 toUserMessage() 取文案，避免 HTTP 状态码、英文报错、
 * 堆栈信息等技术细节直接暴露给用户。
 */

/** 带状态码的统一错误对象：由 API 层抛出，展示层只读取文案。 */
export class FriendlyError extends Error {
  /** HTTP 状态码；网络层失败（连不上服务器）时为 0。 */
  readonly status: number;
  /** 原始错误详情（通常是后端 msg），仅供控制台排查，不直接展示给用户。 */
  readonly detail?: string;

  constructor(status: number, message: string, detail?: string) {
    super(message);
    this.name = 'FriendlyError';
    this.status = status;
    this.detail = detail;
  }
}

/** 各 HTTP 状态码对应的友好文案；0 表示网络层失败。 */
const STATUS_MESSAGES: Record<number, string> = {
  0: '网络连接失败，请检查网络后重试',
  400: '请求内容有误，请调整后重试',
  401: '登录状态已失效，请重新登录',
  403: '当前账号没有执行此操作的权限',
  404: '请求的内容不存在，可能已被删除',
  408: '请求超时，请稍后重试',
  413: '文件过大，请处理后重试',
  429: '操作太频繁，请稍等片刻再试',
  500: '服务暂时不可用，请稍后重试',
  502: '服务暂时不可用，请稍后重试',
  503: '服务正在维护，请稍后重试',
  504: '请求超时，请稍后重试',
};

const DEFAULT_STATUS_MESSAGE = '服务暂时不可用，请稍后重试';
const DEFAULT_FALLBACK = '操作失败，请稍后重试';

/** 取某个状态码的友好文案；未知状态码落到通用兜底。 */
export function describeHttpStatus(status: number): string {
  return STATUS_MESSAGES[status] ?? DEFAULT_STATUS_MESSAGE;
}

/** API 层构造统一错误：用户看到友好文案，原始详情进控制台便于排查。 */
export function createFriendlyError(status: number, detail?: string): FriendlyError {
  if (detail) {
    console.warn(`[api] HTTP ${status}: ${detail}`);
  }
  return new FriendlyError(status, describeHttpStatus(status), detail);
}

/** fetch 网络层异常（DNS 失败、断网、超时中断等）→ 统一错误；用户主动取消原样抛出。 */
export function normalizeFetchFailure(error: unknown): never {
  if (error instanceof DOMException && error.name === 'AbortError') {
    throw error;
  }
  console.warn('[api] 网络请求失败:', error);
  const detail = error instanceof Error ? error.message : String(error);
  throw new FriendlyError(0, describeHttpStatus(0), detail);
}

/** 判断错误是否携带 HTTP 状态码（ FriendlyError 或旧式「HTTP 401: ...」文案）。 */
export function errorStatus(error: unknown): number | null {
  if (error instanceof FriendlyError) {
    return error.status;
  }
  if (error instanceof Error) {
    const matched = /^HTTP (\d{3})/.exec(error.message);
    if (matched) {
      return Number(matched[1]);
    }
  }
  return null;
}

/** 后端/流式事件直接下发的错误信息，仅当本身可读（含中文）时透出，英文报错视为技术细节。 */
function isReadableText(text: string): boolean {
  return /[\u4e00-\u9fff]/.test(text);
}

/**
 * 把任意错误转成面向用户的完整文案：`<操作上下文>：<具体原因>`。
 *
 * @param error    catch 到的错误对象
 * @param fallback 操作上下文（如「评测集加载失败」），兼作无法识别时的兜底文案
 */
export function toUserMessage(error: unknown, fallback: string = DEFAULT_FALLBACK): string {
  if (error instanceof FriendlyError) {
    return `${fallback}：${describeHttpStatus(error.status)}`;
  }
  if (error instanceof Error) {
    if (error.name === 'AbortError') {
      return `${fallback}：请求已取消`;
    }
    if (error.name === 'TypeError') {
      // 浏览器 fetch 断网抛 TypeError("Failed to fetch")，属于网络层问题
      return `${fallback}：${describeHttpStatus(0)}`;
    }
    if (error.message && isReadableText(error.message)) {
      return `${fallback}：${error.message}`;
    }
    return fallback;
  }
  return fallback;
}
