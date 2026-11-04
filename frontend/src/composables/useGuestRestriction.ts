/**
 * 游客 403 统一引导：游客触发 403（功能受限）时，用卡片式提示替换裸错误文案，
 * 并提供「登录 / 继续体验」操作入口。
 *
 * 模块级单例状态（与 api/authRecovery.ts 的注入风格一致）：任意 composable /
 * 组件通过 reportGuestRestriction() 触发，卡片由 App.vue 挂载一次；
 * 「登录」动作由 App 通过 bindRestrictionLoginAction() 注入（打开设置弹窗）。
 */
import { ref } from 'vue';
import { errorStatus } from '../utils/errors';

/** 卡片展示的受限信息：功能名 + 登录后能获得什么的说明。 */
export interface GuestRestrictionInfo {
  /** 受限功能名，如「检索评测」 */
  feature: string;
  /** 受限原因 / 登录引导说明 */
  detail: string;
}

/** 各功能的受限说明；未登记的功能落到通用文案。 */
const FEATURE_DETAILS: Record<string, string> = {
  检索评测: '评测数据集、评测运行与报告导出仅对登录用户开放，登录后即可在评测工作台使用。',
  成本看板: '租户成本与预算仅对登录用户开放，登录后可在设置中查看用量与预算。',
  长期记忆: '长期记忆仅对登录用户开放，登录后可保存和清除个人画像。',
  知识库上传: '文档上传与知识库入库仅对登录用户开放，登录后即可构建私有知识。',
  在线问答: '当前账号没有执行此操作的权限，请使用 API Key 登录后重试。',
};

const GENERIC_DETAIL = '该功能需要登录账号后使用。';

/** 当前待展示的受限信息；null 表示不显示卡片。 */
const restriction = ref<GuestRestrictionInfo | null>(null);

let loginAction: (() => void) | null = null;

/** 游客模式下收到 403 → 视为「功能受限」，交给引导卡片处理。 */
export function isGuestForbidden(error: unknown, guestMode: boolean): boolean {
  return guestMode && errorStatus(error) === 403;
}

/** 触发引导卡片：同一个功能重复触发只更新内容，不叠加。 */
export function reportGuestRestriction(feature: string): void {
  restriction.value = {
    feature,
    detail: FEATURE_DETAILS[feature] ?? GENERIC_DETAIL,
  };
}

export function dismissGuestRestriction(): void {
  restriction.value = null;
}

/** 卡片「登录」按钮：打开 App 注入的登录入口（设置弹窗），并收起卡片。 */
export function openRestrictionLogin(): void {
  loginAction?.();
  dismissGuestRestriction();
}

/** App 在装配期注入登录入口（通常是打开设置弹窗的登录表单）。 */
export function bindRestrictionLoginAction(action: () => void): void {
  loginAction = action;
}

/** 供卡片组件读取的单例状态。 */
export function useGuestRestriction() {
  return {
    restriction,
    dismissGuestRestriction,
    openRestrictionLogin,
  };
}
