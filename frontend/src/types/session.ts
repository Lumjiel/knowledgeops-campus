import type { ChatMessage } from './chat';
import type { ReactTraceStep } from './react';

/** 会话内单个分支：独立的消息流与工具调用轨迹 */
export interface SessionBranch {
  id: string;
  title: string;
  parentBranchId: string | null;
  parentMessageId: string | null;
  updatedAt: number;
  messages: ChatMessage[];
  traceSteps: ReactTraceStep[];
}

/** 会话记录：本地持久化与云端同步的基本单元 */
export interface SessionRecord {
  id: string;
  title: string;
  updatedAt: number;
  modelProfile: string;
  streaming: boolean;
  pinned: boolean;
  archived: boolean;
  workspaceId: string;
  activeBranchId: string;
  branches: SessionBranch[];
}

/** 分支树渲染节点（DFS 展开后的扁平列表） */
export interface BranchTreeItem {
  branch: SessionBranch;
  depth: number;
}

/** 主界面视图：对话 或 检索评测 */
export type ConsoleView = 'chat' | 'evaluation';
