/**
 * 聊天消息相关共享类型：App.vue（状态与请求编排）与
 * components/MessageList.vue（消息列表渲染）共同依赖。
 */

export interface ChatCitationCard {
  index: number;
  file: string;
  page?: number | null;
  chunkIndex: number;
  snippet: string;
}

export interface RetrievalTrace {
  recall: number;
  coarse: number;
  fine: number;
  coarseMs: number;
  fineMs: number;
  degraded: boolean;
  tokenizer?: string;
  coarseReranker?: string;
  fineReranker?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: number;
  citations?: string[];
  citationCards?: ChatCitationCard[];
  evidence?: string[];
  retrievalTrace?: RetrievalTrace;
  matchedQuestion?: string;
  state?: 'pending' | 'streaming' | 'done' | 'error' | 'stopped';
}
