<template>
  <div class="app-shell" :class="{ 'sidebar-closed': !sidebarOpen, 'sidebar-open': sidebarOpen }">
    <aside class="sidebar">
      <div class="brand-block">
        <h1>有章</h1>
        <p class="brand-sub">有章可循 · 有据可查</p>
      </div>

      <button class="new-chat-btn" type="button" @click="createAndSwitchSession">
        ＋ 新建会话
      </button>

      <section class="session-tools">
        <el-input v-model="sessionSearch" size="small" placeholder="搜索会话标题或 ID" clearable />
      </section>

      <section class="session-panel">
        <div class="section-head">
          <p class="section-label">会话</p>
          <span class="section-meta">{{ filteredSessions.length }}/{{ sessionCount }}</span>
        </div>
        <div class="session-list">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === activeSessionId }"
            role="button"
            tabindex="0"
            @click="switchSession(session.id)"
            @keydown.enter.prevent="switchSession(session.id)"
          >
            <div class="session-content">
              <div class="session-title-row">
                <p class="session-title">{{ session.title }}</p>
                <el-tag v-if="session.pinned" size="small" type="success" effect="plain"
                  >置顶</el-tag
                >
                <el-tag v-if="session.archived" size="small" type="info" effect="plain"
                  >归档</el-tag
                >
              </div>
              <p class="session-meta-row">
                {{ session.workspaceId }} · {{ formatTime(session.updatedAt) }} ·
                {{ shortId(session.id) }}
              </p>
            </div>
            <div class="session-actions">
              <button type="button" @click.stop="toggleSessionPin(session.id)">
                {{ session.pinned ? '取消置顶' : '置顶' }}
              </button>
              <button type="button" @click.stop="toggleSessionArchive(session.id)">
                {{ session.archived ? '取消归档' : '归档' }}
              </button>
              <button type="button" class="danger" @click.stop="removeSession(session.id)">
                删除
              </button>
            </div>
          </div>
          <div v-if="filteredSessions.length === 0" class="session-empty">没有匹配会话</div>
        </div>
      </section>

      <el-collapse class="branch-collapse">
        <el-collapse-item name="branch">
          <template #title>
            <span class="branch-collapse-title"
              >分支树（{{ activeSession?.branches.length ?? 0 }} 条）</span
            >
          </template>
          <section class="branch-panel">
            <div class="branch-head-actions">
              <el-button size="small" @click="forkFromCurrent">从当前分叉</el-button>
              <el-button
                size="small"
                :disabled="!activeBranch?.parentBranchId"
                @click="compareWithParent"
              >
                对比父分支
              </el-button>
              <el-button
                size="small"
                :disabled="!activeBranch?.parentBranchId"
                @click="mergeIntoParent"
              >
                合并到父分支
              </el-button>
            </div>
            <div class="branch-list">
              <div
                v-for="node in branchTreeItems"
                :key="node.branch.id"
                class="branch-item"
                :class="{ active: node.branch.id === activeBranch?.id }"
                :style="{ paddingLeft: `${12 + node.depth * 14}px` }"
                role="button"
                tabindex="0"
                @click="switchBranch(node.branch.id)"
                @keydown.enter.prevent="switchBranch(node.branch.id)"
              >
                <span class="branch-line" :style="{ opacity: node.depth > 0 ? 1 : 0 }"></span>
                <div class="branch-content">
                  <p>{{ node.branch.title }}</p>
                  <small>{{ formatTime(node.branch.updatedAt) }}</small>
                </div>
              </div>
              <div v-if="branchTreeItems.length === 0" class="session-empty">暂无分支</div>
            </div>
          </section>
        </el-collapse-item>
      </el-collapse>

      <footer class="sidebar-foot">
        <button
          type="button"
          class="eval-entry"
          @click="activateView(activeView === 'chat' ? 'evaluation' : 'chat')"
        >
          {{ activeView === 'chat' ? '评测模式 · 数据看板' : '← 返回对话' }}
        </button>
      </footer>
    </aside>
    <div
      v-if="sidebarOpen"
      class="sidebar-backdrop"
      aria-hidden="true"
      @click="sidebarOpen = false"
    ></div>

    <main class="workspace">
      <header class="workspace-head">
        <button
          type="button"
          class="sidebar-toggle"
          aria-label="会话列表"
          @click="sidebarOpen = !sidebarOpen"
        >
          ☰
        </button>
        <div v-if="activeView === 'chat'">
          <p class="workspace-kicker">当前会话</p>
          <h2>{{ activeSession?.title || '新会话' }}</h2>
          <p class="workspace-sub">
            {{ modelProfile }} · {{ streaming ? 'SSE 流式' : 'JSON 单次' }}
          </p>
        </div>
        <div v-else>
          <p class="workspace-kicker">检索评测</p>
          <h2>{{ selectedEvalDataset?.name || '评测工作台' }}</h2>
          <p class="workspace-sub">
            {{ evalCurrentRun?.runId || 'no run' }} · {{ evalCurrentRun?.status || 'idle' }}
          </p>
        </div>
        <div v-if="activeView === 'chat'" class="head-actions">
          <el-tag v-if="guestMode" effect="plain" class="guest-chip">游客模式</el-tag>
          <el-tag :type="streamStatusTagType" effect="plain">{{ streamStatusLabel }}</el-tag>
          <span class="stream-detail">{{ streamStatusDetail }}</span>
          <span v-if="costSummary" class="stream-detail"
            >成本: 本月 ${{ costSummary.monthCostUsd.toFixed(4) }} / 预算 ${{
              costSummary.monthlyBudgetUsd.toFixed(4)
            }}</span
          >
          <el-button size="small" text @click="settingsOpen = true">设置</el-button>
        </div>
        <div v-else class="head-actions">
          <el-button size="small" :loading="evalLoading" @click="loadEvalDatasets">刷新</el-button>
          <el-button
            size="small"
            type="primary"
            :loading="evalRunning"
            :disabled="!evalSelectedDatasetId"
            @click="runSelectedEvalDataset"
            >运行评测</el-button
          >
          <el-button
            size="small"
            :disabled="!evalCurrentRun"
            :loading="evalReportExporting"
            @click="downloadEvalReport"
            >导出报告</el-button
          >
          <el-button size="small" :disabled="!evalCurrentRun" @click="markCurrentEvalRunBaseline"
            >设为基线</el-button
          >
        </div>
      </header>

      <template v-if="activeView === 'chat'">
        <div v-if="memoryHint" class="memory-hint">
          <span
            >我记得你是
            <template v-if="memoryHint.college">{{ memoryHint.college }}</template>
            <template v-if="memoryHint.college && memoryHint.grade"> · </template>
            <template v-if="memoryHint.grade">{{ memoryHint.grade }} 级</template>
          </span>
          <button type="button" class="memory-hint-clear" @click="clearMemoryHint">清除</button>
        </div>
        <section
          ref="messageContainer"
          class="messages"
          @scroll="onMessageScroll"
          @click="handleMarkdownClick"
        >
          <div v-if="hydrating" class="hydration-skeleton">
            <div class="skeleton-line lg"></div>
            <div class="skeleton-line"></div>
            <div class="skeleton-line short"></div>
            <div class="skeleton-bubble"></div>
            <div class="skeleton-bubble alt"></div>
          </div>

          <template v-else>
            <div v-if="isEmptyConversation" class="empty-hint">
              <p>从下面的示例问题开始，或直接输入你的问题</p>
            </div>

            <div class="virtual-spacer" :style="{ height: `${virtualTopSpacer}px` }"></div>

            <article
              v-for="entry in virtualMessages"
              :key="entry.item.id"
              :ref="(el) => setMessageRowRef(entry.item.id, el as HTMLElement | null)"
              :data-msg-id="entry.item.id"
              class="message-row"
              :class="[entry.item.role, entry.item.state || 'done']"
            >
              <div class="avatar">{{ entry.item.role === 'user' ? '我' : '章' }}</div>
              <div class="bubble-wrap">
                <div class="bubble-meta">
                  <span>{{ entry.item.role === 'user' ? '你' : '有章' }}</span>
                  <span>{{ formatTime(entry.item.createdAt) }}</span>
                  <span v-if="entry.item.state === 'streaming'" class="status-dot">生成中</span>
                  <span v-if="entry.item.state === 'pending'" class="status-dot">思考中</span>
                </div>

                <div class="bubble">
                  <template v-if="entry.item.role === 'assistant'">
                    <div
                      v-if="entry.item.state === 'pending' && !entry.item.content"
                      class="assistant-skeleton"
                    >
                      <div></div>
                      <div></div>
                      <div></div>
                    </div>
                    <div v-else>
                      <p v-if="entry.item.state === 'streaming'" class="streaming-plain">
                        {{ entry.item.content }}
                      </p>
                      <!-- eslint-disable vue/no-v-html -- 内容经 DOMPurify 消毒 -->
                      <div
                        v-else
                        class="markdown"
                        v-html="renderMarkdown(entry.item.content)"
                      ></div>
                      <!-- eslint-enable vue/no-v-html -->
                      <div v-if="entry.item.citationCards?.length" class="citation-panel">
                        <p class="citation-title">条款依据</p>
                        <div class="citation-card-list">
                          <div
                            v-for="card in entry.item.citationCards"
                            :key="`${entry.item.id}-card-${card.index}`"
                            class="citation-card"
                          >
                            <button
                              type="button"
                              class="citation-card-head"
                              @click="toggleCitationCard(`${entry.item.id}-${card.index}`)"
                            >
                              <span class="citation-card-index">[{{ card.index }}]</span>
                              <span class="citation-card-file">{{
                                prettyFileName(card.file)
                              }}</span>
                              <span v-if="card.page != null" class="citation-card-page"
                                >· P{{ card.page }}</span
                              >
                              <span class="citation-card-caret">{{
                                expandedCitationCards.has(`${entry.item.id}-${card.index}`)
                                  ? '▴'
                                  : '▾'
                              }}</span>
                            </button>
                            <!-- eslint-disable vue/no-v-html -- 内容经 DOMPurify 消毒 -->
                            <p
                              class="citation-card-snippet"
                              :class="{
                                expanded: expandedCitationCards.has(
                                  `${entry.item.id}-${card.index}`,
                                ),
                              }"
                              v-html="renderSnippet(card.snippet, entry.item.matchedQuestion)"
                            ></p>
                            <!-- eslint-enable vue/no-v-html -->
                            <div class="citation-card-actions">
                              <button
                                type="button"
                                class="citation-card-link"
                                @click="openCitation(citationTextOf(card))"
                              >
                                展开原文
                              </button>
                            </div>
                          </div>
                        </div>
                      </div>
                      <div v-else-if="entry.item.citations?.length" class="citation-panel">
                        <p class="citation-title">来源引用</p>
                        <div class="citation-list">
                          <button
                            v-for="(citation, citationIndex) in entry.item.citations"
                            :key="`${entry.item.id}-${citationIndex}`"
                            type="button"
                            class="citation-chip"
                            @click="openCitation(citation)"
                          >
                            [{{ citationIndex + 1 }}] {{ citation }}
                          </button>
                        </div>
                      </div>
                      <div
                        v-if="
                          entry.item.role === 'assistant' &&
                          !hasCitationSource(entry.item) &&
                          !entry.item.content.includes('我是有章')
                        "
                        class="no-citation-fallback"
                      >
                        ⚠ 以上为通用参考信息，暂未匹配到可引用的规章原文，请以学校官方最新通知为准
                      </div>
                      <div
                        v-if="entry.item.role === 'assistant' && entry.item.state === 'done'"
                        class="retrieval-trace-row"
                      >
                        <button
                          type="button"
                          class="retrieval-trace-toggle"
                          @click="expandedTraceOpen = !expandedTraceOpen"
                        >
                          <template v-if="entry.item.retrievalTrace">
                            检索链路 · 召回 {{ entry.item.retrievalTrace.recall }} → 粗排
                            {{ entry.item.retrievalTrace.coarse }} → 精排
                            {{ entry.item.retrievalTrace.fine }} ·
                            {{
                              entry.item.retrievalTrace.coarseMs + entry.item.retrievalTrace.fineMs
                            }}ms ▾
                          </template>
                          <template v-else>检索链路 ▾</template>
                        </button>
                        <div v-if="expandedTraceOpen" class="retrieval-trace-detail">
                          <p v-if="!entry.item.retrievalTrace" class="retrieval-trace-note">
                            轨迹未接入：以下为占位行，非真实耗时
                          </p>
                          <table>
                            <thead>
                              <tr>
                                <th>阶段</th>
                                <th>输入</th>
                                <th>输出</th>
                                <th>耗时</th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr>
                                <td>向量召回</td>
                                <td>—</td>
                                <td>{{ entry.item.retrievalTrace?.recall ?? '—' }}</td>
                                <td>—</td>
                              </tr>
                              <tr>
                                <td>
                                  粗排（{{ entry.item.retrievalTrace?.coarseReranker ?? '—' }}）
                                </td>
                                <td>{{ entry.item.retrievalTrace?.recall ?? '—' }}</td>
                                <td>{{ entry.item.retrievalTrace?.coarse ?? '—' }}</td>
                                <td>
                                  {{
                                    entry.item.retrievalTrace
                                      ? entry.item.retrievalTrace.coarseMs + 'ms'
                                      : '—'
                                  }}
                                </td>
                              </tr>
                              <tr>
                                <td>
                                  精排（{{ entry.item.retrievalTrace?.fineReranker ?? '—' }}）
                                </td>
                                <td>{{ entry.item.retrievalTrace?.coarse ?? '—' }}</td>
                                <td>{{ entry.item.retrievalTrace?.fine ?? '—' }}</td>
                                <td>
                                  {{
                                    entry.item.retrievalTrace
                                      ? entry.item.retrievalTrace.fineMs + 'ms'
                                      : '—'
                                  }}
                                </td>
                              </tr>
                            </tbody>
                          </table>
                          <p
                            v-if="entry.item.retrievalTrace?.degraded"
                            class="retrieval-trace-note"
                          >
                            ⚠ 精排超时，已降级为粗排结果
                          </p>
                        </div>
                      </div>
                      <div v-if="entry.item.evidence?.length" class="evidence-panel">
                        <p class="citation-title">证据片段</p>
                        <ul>
                          <li
                            v-for="(snippet, snippetIndex) in entry.item.evidence"
                            :key="`${entry.item.id}-ev-${snippetIndex}`"
                          >
                            {{ snippet }}
                          </li>
                        </ul>
                      </div>
                      <!-- Agent Trace Timeline -->
                      <div
                        v-if="traceSteps.length && entry.index === virtualMessages.length - 1"
                        class="trace-timeline-panel"
                      >
                        <div class="trace-timeline-header">
                          <span class="trace-timeline-title">Agent 执行轨迹</span>
                          <span class="trace-timeline-meta"
                            >{{ traceSteps.length }} 步 · {{ traceDurationMs }}ms</span
                          >
                        </div>
                        <div class="trace-timeline">
                          <div
                            v-for="(ts, tsIdx) in traceSteps"
                            :key="`trace-${tsIdx}`"
                            class="trace-timeline-step"
                            :class="[
                              `trace-action-${ts.action}`,
                              { 'trace-last': tsIdx === traceSteps.length - 1 },
                            ]"
                          >
                            <div class="trace-timeline-rail">
                              <div class="trace-node"></div>
                              <div
                                v-if="tsIdx < traceSteps.length - 1"
                                class="trace-connector"
                              ></div>
                            </div>
                            <div class="trace-timeline-content">
                              <div class="trace-step-header">
                                <span class="trace-step-label">Step {{ ts.step }}</span>
                                <span class="trace-action-badge" :class="`badge-${ts.action}`">{{
                                  ts.action
                                }}</span>
                              </div>
                              <div v-if="ts.thought" class="trace-thought-block">
                                <span class="trace-field-label">💭 Thought</span>
                                <p>{{ ts.thought }}</p>
                              </div>
                              <div
                                v-if="ts.actionInput && Object.keys(ts.actionInput).length"
                                class="trace-input-block"
                              >
                                <span class="trace-field-label">🔧 Input</span>
                                <pre>{{ JSON.stringify(ts.actionInput, null, 2) }}</pre>
                              </div>
                              <details v-if="ts.observation" class="trace-obs-block">
                                <summary>
                                  <span class="trace-field-label">📋 Observation</span>
                                </summary>
                                <div class="trace-obs-content">
                                  <pre>{{
                                    typeof ts.observation === 'string'
                                      ? ts.observation
                                      : JSON.stringify(ts.observation, null, 2)
                                  }}</pre>
                                </div>
                              </details>
                              <div v-if="tsIdx === 0" class="trace-retrieval-lanes">
                                <span class="trace-field-label">检索四路召回</span>
                                <div class="retrieval-bar">
                                  <div class="retrieval-lane vector" style="width: 40%">
                                    <span>Vector 40%</span>
                                  </div>
                                  <div class="retrieval-lane keyword" style="width: 25%">
                                    <span>Keyword 25%</span>
                                  </div>
                                  <div class="retrieval-lane graph" style="width: 20%">
                                    <span>Graph 20%</span>
                                  </div>
                                  <div class="retrieval-lane web" style="width: 15%">
                                    <span>Web 15%</span>
                                  </div>
                                </div>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </template>

                  <template v-else>
                    <div v-if="editingMessageId === entry.item.id" class="edit-box">
                      <el-input
                        v-model="editingMessageDraft"
                        type="textarea"
                        :rows="3"
                        resize="none"
                      />
                      <div class="edit-actions">
                        <el-button size="small" @click="cancelEditMessage">取消</el-button>
                        <el-button
                          size="small"
                          type="primary"
                          :disabled="!editingMessageDraft.trim() || sending"
                          @click="submitEditAndResend(entry.index, entry.item.id)"
                        >
                          编辑后重发分支
                        </el-button>
                      </div>
                    </div>
                    <p v-else class="plain">{{ entry.item.content }}</p>
                  </template>
                </div>

                <div class="message-actions">
                  <button type="button" @click="copyMessage(entry.item.content)">复制</button>
                  <button
                    v-if="entry.item.role === 'assistant'"
                    type="button"
                    @click="regenerateFrom(entry.index)"
                  >
                    重试分支
                  </button>
                  <button
                    v-if="entry.item.role === 'assistant'"
                    type="button"
                    :disabled="
                      Boolean(answerFeedbackMap[entry.item.id]) ||
                      Boolean(answerFeedbackLoading[entry.item.id])
                    "
                    @click="rateAnswer(entry.index, entry.item, 5)"
                  >
                    👍有帮助
                  </button>
                  <button
                    v-if="entry.item.role === 'assistant'"
                    type="button"
                    :disabled="
                      Boolean(answerFeedbackMap[entry.item.id]) ||
                      Boolean(answerFeedbackLoading[entry.item.id])
                    "
                    @click="rateAnswer(entry.index, entry.item, 1)"
                  >
                    👎待改进
                  </button>
                  <button
                    v-if="entry.item.role === 'user'"
                    type="button"
                    @click="startEditMessage(entry.item)"
                  >
                    编辑后重发
                  </button>
                </div>
              </div>
            </article>

            <div class="virtual-spacer" :style="{ height: `${virtualBottomSpacer}px` }"></div>

            <div v-if="sending && isStreamingResponse" class="thinking">
              {{ streamStatusLabel }} · {{ streamStatusDetail || '处理中...' }}
            </div>
          </template>
        </section>

        <footer class="composer-shell">
          <div class="composer">
            <div v-if="isEmptyConversation" class="quick-prompts">
              <button
                v-for="sample in quickPrompts"
                :key="sample"
                type="button"
                @click="prompt = sample"
              >
                {{ sample }}
              </button>
            </div>
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
                v-model="prompt"
                class="composer-input"
                type="textarea"
                :autosize="{ minRows: 1, maxRows: 5 }"
                resize="none"
                placeholder="输入你的问题…"
                @keydown.enter.exact.prevent="send"
              />
              <el-button
                v-if="sending"
                class="composer-send"
                :disabled="!sending"
                @click="stopGenerating"
                >停止</el-button
              >
              <el-button
                v-else
                class="composer-send"
                type="primary"
                :disabled="!prompt.trim()"
                @click="send"
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

      <section v-else class="evaluation-page">
        <aside class="eval-side-panel">
          <div class="eval-panel-head">
            <div>
              <p class="section-label">数据集</p>
              <strong>{{ evalDatasets.length }}</strong>
            </div>
          </div>

          <div class="eval-dataset-list">
            <button
              v-for="dataset in evalDatasets"
              :key="dataset.datasetId"
              type="button"
              :class="{ active: dataset.datasetId === evalSelectedDatasetId }"
              @click="selectEvalDataset(dataset.datasetId)"
            >
              <span>{{ dataset.name }}</span>
              <small>{{ dataset.caseCount }} cases · {{ shortId(dataset.datasetId) }}</small>
            </button>
            <div v-if="!evalDatasets.length" class="session-empty">暂无评测集</div>
          </div>

          <el-collapse class="eval-create-collapse">
            <el-collapse-item name="create">
              <template #title>
                <span class="branch-collapse-title">＋ 创建评测集</span>
              </template>
              <div class="eval-create-panel">
                <el-input v-model="evalDatasetName" size="small" placeholder="数据集名称" />
                <el-input v-model="evalDatasetDescription" size="small" placeholder="描述" />
                <el-input
                  v-model="evalDatasetJson"
                  class="eval-json-input"
                  type="textarea"
                  :rows="11"
                  resize="none"
                  spellcheck="false"
                />
                <el-button
                  type="primary"
                  :loading="evalCreating"
                  :disabled="!evalDatasetName.trim() || !evalDatasetJson.trim()"
                  @click="createEvalDatasetFromJson"
                  >创建评测集</el-button
                >
              </div>
            </el-collapse-item>
          </el-collapse>
        </aside>

        <section class="eval-main-panel">
          <div class="eval-score-strip">
            <div v-for="metric in evalMetricCards" :key="metric.key" class="eval-metric-card">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.current }}</strong>
              <small :class="metric.deltaClass">{{ metric.delta }}</small>
            </div>
          </div>

          <div class="eval-run-grid">
            <div class="eval-run-summary">
              <p class="section-label">基线</p>
              <strong>{{ evalBaselineRun?.runId || 'none' }}</strong>
              <span>{{ formatRunScore(evalBaselineRun?.metrics.runScore) }}</span>
            </div>
            <div class="eval-run-summary current">
              <p class="section-label">当前</p>
              <strong>{{ evalCurrentRun?.runId || 'none' }}</strong>
              <span>{{ formatRunScore(evalCurrentRun?.metrics.runScore) }}</span>
            </div>
          </div>

          <el-table
            :data="evalCurrentRun?.results ?? []"
            class="eval-result-table"
            height="100%"
            empty-text="暂无评测结果"
          >
            <el-table-column prop="caseId" label="用例" min-width="120" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column label="得分" width="110">
              <template #default="{ row }">{{ formatPercent(row.score) }}</template>
            </el-table-column>
            <el-table-column label="引用覆盖" width="120">
              <template #default="{ row }">{{ formatPercent(row.citationCoverage) }}</template>
            </el-table-column>
            <el-table-column label="延迟" width="120">
              <template #default="{ row }">{{ row.latencyMs }}ms</template>
            </el-table-column>
            <el-table-column prop="question" label="问题" min-width="280" show-overflow-tooltip />
          </el-table>
        </section>
      </section>
    </main>

    <el-dialog
      v-model="settingsOpen"
      title="设置"
      :width="isMobile ? '94%' : '480px'"
      class="settings-dialog"
    >
      <div class="settings-section">
        <p class="settings-label">鉴权与模型</p>
        <el-form label-position="top" size="small">
          <el-form-item label="API Key">
            <el-input
              v-model="apiKeyInput"
              placeholder="输入 API Key（生产建议短时使用）"
              show-password
              type="password"
            />
          </el-form-item>
          <el-form-item label="Tenant (可选)">
            <el-input v-model="tenantInput" placeholder="public" />
          </el-form-item>
          <el-form-item label="Model Profile">
            <el-segmented
              v-model="modelProfile"
              :options="[
                'economy',
                'balanced',
                'quality',
                'ab_auto',
                'quality_first',
                'cost_first',
              ]"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="响应模式">
            <el-switch v-model="streaming" inline-prompt active-text="SSE" inactive-text="JSON" />
          </el-form-item>
        </el-form>
        <div class="auth-buttons">
          <el-button type="primary" :loading="authLoading" @click="handleLogin">换取 JWT</el-button>
          <el-button :disabled="!refreshToken" :loading="refreshing" @click="handleRefresh"
            >刷新</el-button
          >
          <el-button @click="clearAuth">清空</el-button>
        </div>
      </div>

      <div class="settings-section">
        <p class="settings-label">工作区</p>
        <div class="workspace-manager">
          <el-select v-model="activeWorkspaceId" size="small" class="workspace-select">
            <el-option
              v-for="workspace in workspaceOptions"
              :key="workspace"
              :label="workspace"
              :value="workspace"
            />
          </el-select>
          <el-input
            v-model="workspaceDraft"
            size="small"
            class="workspace-input"
            placeholder="新工作区"
            @keydown.enter.prevent="createWorkspace"
          />
          <el-button size="small" @click="createWorkspace">创建并切换</el-button>
        </div>
      </div>

      <div class="settings-section">
        <p class="settings-label">会话列表与同步</p>
        <div class="workspace-manager">
          <el-select v-model="workspaceFilter" size="small" class="tool-select">
            <el-option label="全部工作区" value="all" />
            <el-option
              v-for="workspace in workspaceOptions"
              :key="workspace"
              :label="workspace"
              :value="workspace"
            />
          </el-select>
          <el-switch
            v-model="showArchivedSessions"
            size="small"
            inline-prompt
            active-text="含归档"
            inactive-text="隐藏归档"
          />
        </div>
        <div class="cloud-row">
          <el-button
            size="small"
            :loading="cloudSyncing"
            :disabled="!canUseRemoteSync"
            @click="loadSessionsFromCloud"
            >云端拉取</el-button
          >
          <el-button
            size="small"
            type="primary"
            :loading="cloudSyncing"
            :disabled="!canUseRemoteSync"
            @click="syncActiveSessionToCloud"
            >保存当前会话到云端</el-button
          >
        </div>
      </div>
      <template #footer>
        <el-button size="small" type="danger" plain @click="clearConversation"
          >清空当前会话</el-button
        >
        <el-button size="small" type="primary" @click="settingsOpen = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import bashLang from 'highlight.js/lib/languages/bash';
import javaLang from 'highlight.js/lib/languages/java';
import javascriptLang from 'highlight.js/lib/languages/javascript';
import jsonLang from 'highlight.js/lib/languages/json';
import markdownLang from 'highlight.js/lib/languages/markdown';
import pythonLang from 'highlight.js/lib/languages/python';
import sqlLang from 'highlight.js/lib/languages/sql';
import typescriptLang from 'highlight.js/lib/languages/typescript';
import xmlLang from 'highlight.js/lib/languages/xml';
import yamlLang from 'highlight.js/lib/languages/yaml';
import { ElMessage } from 'element-plus';
import { marked } from 'marked';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  compareSessionBranches,
  createEvalDataset,
  exchangeApiKey,
  exchangeGuestToken,
  exportEvalRunReport,
  getEvalComparison,
  getTenantCostSummary,
  listEvalDatasets,
  markEvalRunBaseline,
  listSessionStates,
  mergeSessionBranches,
  reactChat,
  refreshJwt,
  uploadPdf,
  saveSessionState,
  setSessionArchived,
  setSessionPinned,
  streamReactChat,
  submitAnswerFeedback,
  triggerEvalRun,
} from './api/client';
import type {
  EvalCaseCreate,
  EvalComparison,
  EvalDataset,
  EvalMetricSummary,
  EvalRun,
  ReactChatResponse,
  ReactErrorEvent,
  ReactTokenEvent,
  ReactTraceStep,
  SessionState,
  TenantCostSummary,
} from './types/react';

interface ChatCitationCard {
  index: number;
  file: string;
  page?: number | null;
  chunkIndex: number;
  snippet: string;
}

interface RetrievalTrace {
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

interface ChatMessage {
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

interface SessionBranch {
  id: string;
  title: string;
  parentBranchId: string | null;
  parentMessageId: string | null;
  updatedAt: number;
  messages: ChatMessage[];
  traceSteps: ReactTraceStep[];
}

interface SessionRecord {
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

interface BranchTreeItem {
  branch: SessionBranch;
  depth: number;
}

interface MessageMetric {
  item: ChatMessage;
  index: number;
  offset: number;
  height: number;
}

type StreamPhase = 'idle' | 'thinking' | 'tool' | 'streaming' | 'done' | 'error' | 'stopped';
type ConsoleView = 'chat' | 'evaluation';

interface EvalMetricCard {
  key: keyof EvalMetricSummary;
  label: string;
  current: string;
  delta: string;
  deltaClass: string;
}

const STORAGE_KEY = 'knowledgeops-agent-react-console-v2';
const LEGACY_STORAGE_KEY = 'knowledgeops-agent-react-console';
const DEFAULT_SYSTEM_MESSAGE =
  '你好，我是有章。校园规章相关的问题都可以问我，回答会附上制度原文出处。';
const DEFAULT_WORKSPACE = 'default';
const ESTIMATED_ROW_HEIGHT = 156;
const OVERSCAN_COUNT = 8;
const DEFAULT_EVAL_DATASET = [
  {
    caseId: 'rag_001',
    category: 'rag_recall',
    chatId: 'eval-rag-a',
    question: '根据知识库，课程预约需要哪些字段？',
    expectedKeywords: ['课程', '姓名', '联系方式', '校区'],
    forbiddenKeywords: ['我不知道', '无法回答'],
  },
  {
    caseId: 'rag_002',
    category: 'rag_precision',
    chatId: 'eval-rag-b',
    question: '请总结这个 PDF 里和高温健康风险相关的内容。',
    expectedKeywords: ['高温', '风险'],
    forbiddenKeywords: ['与问题无关', '瞎编'],
  },
  {
    caseId: 'rag_003',
    category: 'citation_coverage',
    chatId: 'eval-rag-b',
    question: '回答时列出引用来源，并说明高温风险处置建议。',
    expectedKeywords: ['引用', '高温', '风险'],
    expectedCitations: ['heat'],
  },
];

hljs.registerLanguage('bash', bashLang);
hljs.registerLanguage('java', javaLang);
hljs.registerLanguage('javascript', javascriptLang);
hljs.registerLanguage('json', jsonLang);
hljs.registerLanguage('markdown', markdownLang);
hljs.registerLanguage('python', pythonLang);
hljs.registerLanguage('sql', sqlLang);
hljs.registerLanguage('typescript', typescriptLang);
hljs.registerLanguage('xml', xmlLang);
hljs.registerLanguage('yaml', yamlLang);

function safeParse(raw: string | null): Record<string, unknown> {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function toBase64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function fromBase64(value: string): string {
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

const renderer = new marked.Renderer();
renderer.code = ((token: { text: string; lang?: string }) => {
  const rawCode = token.text ?? '';
  const lang = token.lang?.trim().toLowerCase().split(/\s+/)[0] ?? 'plaintext';
  const language = hljs.getLanguage(lang) ? lang : 'plaintext';
  const highlighted =
    language === 'plaintext'
      ? escapeHtml(rawCode)
      : hljs.highlight(rawCode, { language, ignoreIllegals: true }).value;

  const lines = highlighted.split('\n');
  const numbered = lines
    .map((line, index) => {
      const content = line || '&nbsp;';
      return `<span class="code-line"><span class="line-no">${index + 1}</span><span class="line-content">${content}</span></span>`;
    })
    .join('');

  const payload = escapeHtml(toBase64(rawCode));

  return `<div class="code-block"><div class="code-toolbar"><span class="code-lang">${language}</span><button class="copy-code-btn" type="button" data-code="${payload}">复制代码</button></div><pre><code class="hljs language-${language}">${numbered}</code></pre></div>`;
}) as typeof renderer.code;

marked.use({
  gfm: true,
  breaks: true,
  renderer,
});

function createChatId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `react-${Date.now()}-${suffix}`;
}

function createBranchId(): string {
  const suffix = Math.random().toString(36).slice(2, 8);
  return `branch-${Date.now()}-${suffix}`;
}

function createMessage(role: ChatMessage['role'], content: string): ChatMessage {
  return {
    id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    createdAt: Date.now(),
    state: 'done',
  };
}

function normalizeMessage(raw: unknown): ChatMessage {
  const candidate = (raw ?? {}) as Partial<ChatMessage>;
  const role = candidate.role === 'assistant' ? 'assistant' : 'user';
  return {
    id: candidate.id || `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content: typeof candidate.content === 'string' ? candidate.content : '',
    createdAt: typeof candidate.createdAt === 'number' ? candidate.createdAt : Date.now(),
    citations: Array.isArray(candidate.citations)
      ? candidate.citations.map((item) => String(item).trim()).filter(Boolean)
      : [],
    citationCards: Array.isArray(candidate.citationCards)
      ? candidate.citationCards.filter(
          (card): card is ChatCitationCard =>
            Boolean(card) && typeof card.file === 'string' && typeof card.snippet === 'string',
        )
      : [],
    evidence: Array.isArray(candidate.evidence)
      ? candidate.evidence.map((item) => String(item).trim()).filter(Boolean)
      : [],
    state: candidate.state || 'done',
  };
}

const expandedCitationCards = ref<Set<string>>(new Set());
const expandedTraceOpen = ref(false);

interface MemoryHint {
  college?: string | null;
  grade?: number | null;
  memoryId?: string;
}

const memoryHint = ref<MemoryHint | null>(null);

function memoryApiBase(): string {
  return (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
}

function memoryAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const auth = authContext();
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`;
  } else if (auth.apiKey) {
    headers['X-API-Key'] = auth.apiKey;
  }
  return headers;
}

/** A6 记忆提示条：数据来自 GET /api/memory，接口不可用或无槽位则不显示 */
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

function toggleCitationCard(key: string): void {
  const next = new Set(expandedCitationCards.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedCitationCards.value = next;
}

function citationTextOf(card: ChatCitationCard): string {
  return `source=${card.file}.pdf, chunk=${card.chunkIndex}`;
}

function hasCitationSource(item: ChatMessage): boolean {
  return Boolean(item.citationCards?.length || item.citations?.length);
}

/** 从用户问题中提取 ≥2 字的中文词，用于 snippet 高亮；无匹配返回原文 */
function questionKeywords(question: string | undefined): string[] {
  if (!question) {
    return [];
  }
  const matches = question.match(/[\u4e00-\u9fa5]{2,}/g) ?? [];
  return [...new Set(matches)].filter((word) => word.length >= 2);
}

/**
 * snippet 渲染：先 HTML 转义再高亮（防注入），expanded 时展示全文。
 * 高亮只包 <mark>，匹配不到就原样返回，不报错。
 */
function prettyFileName(raw: string | undefined): string {
  if (!raw) return '规章制度';
  return (
    raw
      .replace(/\.pdf$/i, '')
      .replace(/[_-]+/g, ' ')
      .trim() || '规章制度'
  );
}

function renderSnippet(snippet: string, question?: string): string {
  const text = String(snippet ?? '');
  const safe = escapeHtml(text);
  const keywords = questionKeywords(question);
  if (!keywords.length) {
    return safe;
  }
  const pattern = new RegExp(
    `(${keywords.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`,
    'g',
  );
  return safe.replace(pattern, '<mark>$1</mark>');
}

function deriveTitle(text: string): string {
  const clean = text.trim().replace(/\s+/g, ' ');
  if (!clean) {
    return '新会话';
  }
  return clean.length > 28 ? `${clean.slice(0, 28)}...` : clean;
}

function createRootBranch(): SessionBranch {
  return {
    id: createBranchId(),
    title: '主分支',
    parentBranchId: null,
    parentMessageId: null,
    updatedAt: Date.now(),
    messages: [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)],
    traceSteps: [],
  };
}

function normalizeBranch(raw: unknown): SessionBranch {
  const candidate = (raw ?? {}) as Partial<SessionBranch>;
  const messages = Array.isArray(candidate.messages)
    ? candidate.messages.map(normalizeMessage)
    : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

  return {
    id: candidate.id || createBranchId(),
    title: candidate.title || '分支',
    parentBranchId: candidate.parentBranchId ?? null,
    parentMessageId: candidate.parentMessageId ?? null,
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    messages,
    traceSteps: Array.isArray(candidate.traceSteps) ? candidate.traceSteps : [],
  };
}

function normalizeSession(raw: unknown): SessionRecord {
  const candidate = (raw ?? {}) as Record<string, unknown>;
  let branches: SessionBranch[] = [];

  if (Array.isArray(candidate.branches) && candidate.branches.length > 0) {
    branches = candidate.branches.map((item) => normalizeBranch(item));
  } else {
    const fallbackMessages = Array.isArray(candidate.messages)
      ? candidate.messages.map(normalizeMessage)
      : [createMessage('assistant', DEFAULT_SYSTEM_MESSAGE)];

    branches = [
      {
        id: createBranchId(),
        title: '主分支',
        parentBranchId: null,
        parentMessageId: null,
        updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
        messages: fallbackMessages,
        traceSteps: Array.isArray(candidate.traceSteps)
          ? (candidate.traceSteps as ReactTraceStep[])
          : [],
      },
    ];
  }

  const activeBranchId =
    typeof candidate.activeBranchId === 'string' ? candidate.activeBranchId : branches[0].id;

  return {
    id: typeof candidate.id === 'string' ? candidate.id : createChatId(),
    title: typeof candidate.title === 'string' ? candidate.title : '新会话',
    updatedAt: typeof candidate.updatedAt === 'number' ? candidate.updatedAt : Date.now(),
    modelProfile: typeof candidate.modelProfile === 'string' ? candidate.modelProfile : 'balanced',
    streaming: Boolean(candidate.streaming ?? true),
    pinned: Boolean(candidate.pinned),
    archived: Boolean(candidate.archived),
    workspaceId:
      typeof candidate.workspaceId === 'string' ? candidate.workspaceId : DEFAULT_WORKSPACE,
    activeBranchId,
    branches,
  };
}

function createSession(): SessionRecord {
  const id = createChatId();
  const rootBranch = createRootBranch();

  return {
    id,
    title: '新会话',
    updatedAt: Date.now(),
    modelProfile: 'balanced',
    streaming: true,
    pinned: false,
    archived: false,
    workspaceId: DEFAULT_WORKSPACE,
    activeBranchId: rootBranch.id,
    branches: [rootBranch],
  };
}

function formatTime(value: number): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function shortId(id: string): string {
  return id.slice(0, 10);
}

// Module-level hook: force rel="noopener noreferrer" on every link with
// target="_blank" rendered from LLM output. Without this, a prompt-injected
// response could open a new tab and the new tab's JS would be able to reach
// back to window.opener.location of the original page (reverse-tabnabbing).
// Registered once at module load; DOMPurify hooks are keyed by event name and
// replace prior registrations, so this is safe across re-renders.
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.getAttribute('target') === '_blank') {
    const existing = (node.getAttribute('rel') || '').toLowerCase();
    const merged = new Set(existing.split(/\s+/).filter(Boolean));
    merged.add('noopener');
    merged.add('noreferrer');
    node.setAttribute('rel', Array.from(merged).join(' '));
  }
});

function renderMarkdown(content: string): string {
  if (!content?.trim()) {
    return '<p>等待模型输出...</p>';
  }
  const html = marked.parse(content) as string;
  // Defense-in-depth: explicitly forbid inline event handlers, javascript:
  // URLs, and target=_blank without rel=noopener. DOMPurify already strips
  // the dangerous forms (script, onerror, javascript:), but the defaults
  // leave target and a few other attributes alone, which is enough to
  // enable reverse-tabnabbing on links rendered from LLM output.
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['data-code'],
    ALLOWED_ATTR: [
      'href',
      'title',
      'alt',
      'src',
      'name',
      'target',
      'rel',
      'class',
      'id',
      'data-code',
      'data-line',
      'colspan',
      'rowspan',
      'align',
    ],
    FORBID_ATTR: ['style', 'onload', 'onclick', 'onerror', 'onmouseover'],
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input'],
  });
}

const cached = safeParse(localStorage.getItem(STORAGE_KEY));
const legacy = safeParse(localStorage.getItem(LEGACY_STORAGE_KEY));
const bootstrap = Object.keys(cached).length > 0 ? cached : legacy;

const darkMode = ref(Boolean(bootstrap.darkMode));
const activeView = ref<ConsoleView>(bootstrap.activeView === 'evaluation' ? 'evaluation' : 'chat');
const settingsOpen = ref(false);
const apiKeyInput = ref((bootstrap.apiKey as string | undefined) ?? '');
const tenantInput = ref((bootstrap.tenantId as string | undefined) ?? '');
const token = ref((bootstrap.token as string | undefined) ?? '');
const refreshToken = ref((bootstrap.refreshToken as string | undefined) ?? '');
const sessionSearch = ref((bootstrap.sessionSearch as string | undefined) ?? '');
const workspaceFilter = ref((bootstrap.workspaceFilter as string | undefined) ?? 'all');
const showArchivedSessions = ref(Boolean(bootstrap.showArchivedSessions));

const sessions = ref<SessionRecord[]>(
  Array.isArray(bootstrap.sessions) && bootstrap.sessions.length > 0
    ? (bootstrap.sessions as unknown[]).map((item) => normalizeSession(item))
    : [createSession()],
);

const activeSessionId = ref(
  (bootstrap.activeSessionId as string | undefined) ?? sessions.value[0].id,
);

const activeSession = computed(() => {
  const found = sessions.value.find((item) => item.id === activeSessionId.value);
  return found ?? sessions.value[0];
});

const activeBranch = computed(() => {
  const session = activeSession.value;
  return (
    session.branches.find((branch) => branch.id === session.activeBranchId) ?? session.branches[0]
  );
});

const chatId = ref(activeSession.value.id);
const modelProfile = ref(activeSession.value.modelProfile);
const streaming = ref(activeSession.value.streaming);
const messages = ref<ChatMessage[]>([...activeBranch.value.messages]);
const traceSteps = ref<ReactTraceStep[]>([...activeBranch.value.traceSteps]);
const traceDurationMs = computed(() => {
  if (!traceSteps.value.length) return 0;
  // Estimate ~2s per step as rough timing if not available
  return traceSteps.value.length * 2000;
});

const authLoading = ref(false);
const refreshing = ref(false);
const sending = ref(false);
const isStreamingResponse = ref(false);
const hydrating = ref(true);
const prompt = ref('');
const messageContainer = ref<HTMLElement | null>(null);
const currentAbortController = ref<AbortController | null>(null);
const workspaceDraft = ref('');
const editingMessageId = ref<string | null>(null);
const editingMessageDraft = ref('');
const streamPhase = ref<StreamPhase>('idle');
const streamStatusDetail = ref('');
const cloudSyncing = ref(false);
const costSummary = ref<TenantCostSummary | null>(null);
const answerFeedbackMap = ref<Record<string, number>>({});
const answerFeedbackLoading = ref<Record<string, boolean>>({});
const evalDatasets = ref<EvalDataset[]>([]);
const evalSelectedDatasetId = ref((bootstrap.evalSelectedDatasetId as string | undefined) ?? '');
const evalComparison = ref<EvalComparison | null>(null);
const evalLoading = ref(false);
const evalCreating = ref(false);
const evalRunning = ref(false);
const evalReportExporting = ref(false);
const evalDatasetName = ref('RAG Evaluation Studio Demo');
const evalDatasetDescription = ref('RAG baseline regression set');
const evalDatasetJson = ref(JSON.stringify(DEFAULT_EVAL_DATASET, null, 2));

const messageHeights = ref<Record<string, number>>({});
const viewportHeight = ref(0);
const scrollTop = ref(0);
const messageRowElements = new Map<string, HTMLElement>();
let resizeObserver: ResizeObserver | null = null;
let streamResetTimer: number | null = null;

const quickPrompts = [
  '旷课 50 学时会被怎么处分',
  '缓考怎么办',
  '转专业需要什么条件',
  '被记过了还能入党吗',
];

const sessionCount = computed(() => sessions.value.length);

const canUseRemoteSync = computed(() => Boolean(token.value || apiKeyInput.value.trim()));

const selectedEvalDataset = computed(() =>
  evalDatasets.value.find((dataset) => dataset.datasetId === evalSelectedDatasetId.value),
);

const evalCurrentRun = computed<EvalRun | null>(() => evalComparison.value?.current ?? null);

const evalBaselineRun = computed<EvalRun | null>(() => evalComparison.value?.baseline ?? null);

const evalMetricCards = computed<EvalMetricCard[]>(() => {
  const current = evalCurrentRun.value?.metrics;
  const baseline = evalBaselineRun.value?.metrics;
  return [
    metricCard('runScore', '总评分', current, baseline, 'percent'),
    metricCard('retrievalHitRate', '检索命中', current, baseline, 'percent'),
    metricCard('citationCoverageRate', '引用覆盖', current, baseline, 'percent'),
    metricCard('answerFaithfulnessScore', '忠实度', current, baseline, 'percent'),
    metricCard('avgLatencyMs', '平均延迟', current, baseline, 'ms', true),
    metricCard('failureRate', '失败率', current, baseline, 'percent', true),
  ];
});

const workspaceOptions = computed(() => {
  const options = new Set<string>([DEFAULT_WORKSPACE]);
  sessions.value.forEach((session) => {
    options.add(session.workspaceId || DEFAULT_WORKSPACE);
  });
  return [...options].sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

const activeWorkspaceId = computed({
  get: () => activeSession.value.workspaceId,
  set: (value: string) => {
    activeSession.value.workspaceId = value || DEFAULT_WORKSPACE;
    persistState();
  },
});

const orderedSessions = computed(() =>
  [...sessions.value].sort((a, b) => {
    if (a.pinned !== b.pinned) {
      return Number(b.pinned) - Number(a.pinned);
    }
    return b.updatedAt - a.updatedAt;
  }),
);

const filteredSessions = computed(() => {
  const keyword = sessionSearch.value.trim().toLowerCase();
  return orderedSessions.value.filter((session) => {
    if (!showArchivedSessions.value && session.archived) {
      return false;
    }

    if (workspaceFilter.value !== 'all' && session.workspaceId !== workspaceFilter.value) {
      return false;
    }

    if (!keyword) {
      return true;
    }

    return (
      session.title.toLowerCase().includes(keyword) || session.id.toLowerCase().includes(keyword)
    );
  });
});

const branchTreeItems = computed<BranchTreeItem[]>(() => {
  const session = activeSession.value;
  const children = new Map<string | null, SessionBranch[]>();

  session.branches.forEach((branch) => {
    const key = branch.parentBranchId ?? null;
    if (!children.has(key)) {
      children.set(key, []);
    }
    children.get(key)?.push(branch);
  });

  children.forEach((list) => {
    list.sort((a, b) => b.updatedAt - a.updatedAt);
  });

  const result: BranchTreeItem[] = [];

  function dfs(parentId: string | null, depth: number): void {
    const list = children.get(parentId) ?? [];
    list.forEach((branch) => {
      result.push({ branch, depth });
      dfs(branch.id, depth + 1);
    });
  }

  dfs(null, 0);
  return result;
});

const isEmptyConversation = computed(() => {
  const nonSystem = messages.value.filter((item) => item.role === 'user');
  return nonSystem.length === 0;
});

const messageMetrics = computed<MessageMetric[]>(() => {
  let offset = 0;
  return messages.value.map((item, index) => {
    const height = messageHeights.value[item.id] ?? ESTIMATED_ROW_HEIGHT;
    const metric = {
      item,
      index,
      offset,
      height,
    };
    offset += height;
    return metric;
  });
});

const totalVirtualHeight = computed(() => {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }
  const last = metrics[metrics.length - 1];
  return last.offset + last.height;
});

function findMetricIndexByOffset(targetOffset: number): number {
  const metrics = messageMetrics.value;
  if (metrics.length === 0) {
    return 0;
  }

  let left = 0;
  let right = metrics.length - 1;
  let answer = metrics.length - 1;

  while (left <= right) {
    const mid = (left + right) >> 1;
    const metric = metrics[mid];
    if (metric.offset + metric.height >= targetOffset) {
      answer = mid;
      right = mid - 1;
    } else {
      left = mid + 1;
    }
  }

  return answer;
}

const virtualRange = computed(() => {
  const total = messages.value.length;
  if (total === 0) {
    return { start: 0, end: -1 };
  }

  const startAnchor = Math.max(0, scrollTop.value - viewportHeight.value * 0.8);
  const endAnchor = scrollTop.value + viewportHeight.value * 1.8;

  const start = Math.max(0, findMetricIndexByOffset(startAnchor) - OVERSCAN_COUNT);
  const end = Math.min(total - 1, findMetricIndexByOffset(endAnchor) + OVERSCAN_COUNT);

  return { start, end };
});

const virtualMessages = computed(() => {
  const metrics = messageMetrics.value;
  const { start, end } = virtualRange.value;
  if (end < start) {
    return [];
  }
  return metrics.slice(start, end + 1);
});

const virtualTopSpacer = computed(() => virtualMessages.value[0]?.offset ?? 0);

const virtualBottomSpacer = computed(() => {
  if (virtualMessages.value.length === 0) {
    return 0;
  }

  const last = virtualMessages.value[virtualMessages.value.length - 1];
  return Math.max(0, totalVirtualHeight.value - (last.offset + last.height));
});

const streamStatusLabel = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return '思考中';
    case 'tool':
      return '工具调用中';
    case 'streaming':
      return '输出中';
    case 'done':
      return '已完成';
    case 'error':
      return '失败';
    case 'stopped':
      return '已停止';
    default:
      return '空闲';
  }
});

const streamStatusTagType = computed(() => {
  switch (streamPhase.value) {
    case 'thinking':
      return 'warning';
    case 'tool':
      return 'success';
    case 'streaming':
      return 'primary';
    case 'done':
      return 'success';
    case 'error':
      return 'danger';
    case 'stopped':
      return 'info';
    default:
      return 'info';
  }
});

function scheduleStreamReset(): void {
  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
  }
  streamResetTimer = window.setTimeout(() => {
    streamPhase.value = 'idle';
    streamStatusDetail.value = '';
    streamResetTimer = null;
  }, 1800);
}

function persistState(): void {
  localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      darkMode: darkMode.value,
      activeView: activeView.value,
      apiKey: apiKeyInput.value,
      tenantId: tenantInput.value,
      token: token.value,
      refreshToken: refreshToken.value,
      evalSelectedDatasetId: evalSelectedDatasetId.value,
      activeSessionId: activeSessionId.value,
      sessionSearch: sessionSearch.value,
      workspaceFilter: workspaceFilter.value,
      showArchivedSessions: showArchivedSessions.value,
      sessions: sessions.value,
    }),
  );
}

function authContext() {
  return {
    token: token.value || undefined,
    apiKey: apiKeyInput.value || undefined,
    tenantId: tenantInput.value || undefined,
  };
}

function activateView(view: ConsoleView): void {
  activeView.value = view;
  persistState();
  if (view === 'evaluation' && evalDatasets.value.length === 0 && !evalLoading.value) {
    void loadEvalDatasets();
  }
}

function metricCard(
  key: keyof EvalMetricSummary,
  label: string,
  current: EvalMetricSummary | undefined,
  baseline: EvalMetricSummary | undefined,
  unit: 'percent' | 'ms',
  lowerIsBetter = false,
): EvalMetricCard {
  const currentValue = current?.[key];
  const baselineValue = baseline?.[key];
  const hasCurrent = typeof currentValue === 'number';
  const hasBaseline = typeof baselineValue === 'number';
  let delta = 'baseline -';
  let deltaClass = 'neutral';
  if (hasCurrent && hasBaseline) {
    const diff = currentValue - baselineValue;
    const good = lowerIsBetter ? diff <= 0 : diff >= 0;
    delta = `${diff >= 0 ? '+' : ''}${formatMetricValue(diff, unit)}`;
    deltaClass = good ? 'good' : 'bad';
  }
  return {
    key,
    label,
    current: hasCurrent ? formatMetricValue(currentValue, unit) : '-',
    delta,
    deltaClass,
  };
}

function formatMetricValue(value: number, unit: 'percent' | 'ms'): string {
  if (unit === 'ms') {
    return `${value.toFixed(0)}ms`;
  }
  return formatPercent(value);
}

function formatPercent(value: number | undefined): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  return `${(value * 100).toFixed(1)}%`;
}

function formatRunScore(value: number | undefined): string {
  return typeof value === 'number' ? formatPercent(value) : '-';
}

function normalizeEvalCase(raw: Record<string, unknown>, index: number): EvalCaseCreate {
  const expectedKeywords = raw.expectedKeywords ?? raw.expected_keywords;
  const expectedCitations = raw.expectedCitations ?? raw.expected_citations;
  const forbiddenKeywords = raw.forbiddenKeywords ?? raw.forbidden_keywords;
  return {
    caseId: String(raw.caseId ?? raw.id ?? `case-${index + 1}`).trim(),
    category: String(raw.category ?? 'rag').trim(),
    chatId: String(raw.chatId ?? raw.chat_id ?? '').trim(),
    question: String(raw.question ?? '').trim(),
    expectedKeywords: Array.isArray(expectedKeywords) ? expectedKeywords.map(String) : [],
    expectedCitations: Array.isArray(expectedCitations) ? expectedCitations.map(String) : [],
    forbiddenKeywords: Array.isArray(forbiddenKeywords) ? forbiddenKeywords.map(String) : [],
  };
}

function parseEvalDatasetJson(): EvalCaseCreate[] {
  const parsed = JSON.parse(evalDatasetJson.value) as unknown;
  let rawCases: unknown[] = [];
  if (Array.isArray(parsed)) {
    rawCases = parsed;
  } else if (parsed && typeof parsed === 'object') {
    const objectValue = parsed as Record<string, unknown>;
    if (Array.isArray(objectValue.cases)) {
      rawCases = objectValue.cases;
    } else if (objectValue.paths && typeof objectValue.paths === 'object') {
      Object.values(objectValue.paths as Record<string, unknown>).forEach((pathValue) => {
        if (pathValue && typeof pathValue === 'object') {
          const cases = (pathValue as Record<string, unknown>).cases;
          if (Array.isArray(cases)) {
            rawCases.push(...cases);
          }
        }
      });
    }
  }

  const cases = rawCases
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(normalizeEvalCase)
    .filter((item) => item.question);
  if (cases.length === 0) {
    throw new Error('评测集 JSON 没有可用 case');
  }
  return cases;
}

async function loadEvalDatasets(): Promise<void> {
  evalLoading.value = true;
  try {
    evalDatasets.value = await listEvalDatasets(authContext());
    if (!evalSelectedDatasetId.value && evalDatasets.value.length > 0) {
      evalSelectedDatasetId.value = evalDatasets.value[0].datasetId;
    }
    if (evalSelectedDatasetId.value) {
      await loadEvalComparison(evalSelectedDatasetId.value);
    }
    persistState();
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集加载失败';
    ElMessage.error(message);
  } finally {
    evalLoading.value = false;
  }
}

async function loadEvalComparison(datasetId: string): Promise<void> {
  try {
    evalComparison.value = await getEvalComparison(datasetId, authContext());
  } catch (error) {
    evalComparison.value = null;
    const message = error instanceof Error ? error.message : '评测结果加载失败';
    ElMessage.error(message);
  }
}

async function selectEvalDataset(datasetId: string): Promise<void> {
  evalSelectedDatasetId.value = datasetId;
  persistState();
  await loadEvalComparison(datasetId);
}

async function createEvalDatasetFromJson(): Promise<void> {
  evalCreating.value = true;
  try {
    const created = await createEvalDataset(
      {
        name: evalDatasetName.value.trim(),
        description: evalDatasetDescription.value.trim(),
        cases: parseEvalDatasetJson(),
      },
      authContext(),
    );
    evalDatasets.value = [created, ...evalDatasets.value];
    evalSelectedDatasetId.value = created.datasetId;
    await loadEvalComparison(created.datasetId);
    persistState();
    ElMessage.success('评测集已创建');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测集创建失败';
    ElMessage.error(message);
  } finally {
    evalCreating.value = false;
  }
}

async function runSelectedEvalDataset(): Promise<void> {
  if (!evalSelectedDatasetId.value) {
    return;
  }
  evalRunning.value = true;
  try {
    const run = await triggerEvalRun(
      evalSelectedDatasetId.value,
      {
        modelProfile: modelProfile.value,
        chatIdPrefix: 'eval-studio',
      },
      authContext(),
    );
    evalComparison.value = {
      dataset: selectedEvalDataset.value ??
        evalComparison.value?.dataset ?? {
          datasetId: run.datasetId,
          tenantId: run.tenantId,
          name: run.datasetId,
          caseCount: run.metrics.totalCases,
          createdAt: run.createdAt,
          updatedAt: run.createdAt,
        },
      baseline: evalComparison.value?.baseline ?? null,
      current: run,
    };
    ElMessage.success('评测完成');
  } catch (error) {
    const message = error instanceof Error ? error.message : '评测运行失败';
    ElMessage.error(message);
  } finally {
    evalRunning.value = false;
  }
}

async function markCurrentEvalRunBaseline(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  try {
    await markEvalRunBaseline(run.runId, authContext());
    await loadEvalComparison(run.datasetId);
    ElMessage.success('Baseline 已更新');
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Baseline 更新失败';
    ElMessage.error(message);
  }
}

async function downloadEvalReport(): Promise<void> {
  const run = evalCurrentRun.value;
  if (!run) {
    return;
  }
  evalReportExporting.value = true;
  try {
    const report = await exportEvalRunReport(run.runId, authContext());
    const blob = new Blob([report], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `rag-evaluation-${run.runId}.md`;
    link.click();
    URL.revokeObjectURL(url);
    ElMessage.success('报告已导出');
  } catch (error) {
    const message = error instanceof Error ? error.message : '报告导出失败';
    ElMessage.error(message);
  } finally {
    evalReportExporting.value = false;
  }
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

function normalizeRemoteSession(raw: unknown): SessionRecord {
  return normalizeSession(raw);
}

async function loadSessionsFromCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  cloudSyncing.value = true;
  try {
    const page = await listSessionStates(authContext(), {
      page: 1,
      pageSize: 200,
      includeArchived: true,
    });
    if (Array.isArray(page.items) && page.items.length > 0) {
      sessions.value = page.items.map((item) => normalizeRemoteSession(item));
      const current =
        sessions.value.find((item) => item.id === activeSessionId.value) ?? sessions.value[0];
      loadSession(current.id);
      persistState();
    }
    await refreshCostSummary();
    ElMessage.success('已从云端加载会话');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端拉取失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

async function syncActiveSessionToCloud(): Promise<void> {
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再同步');
    return;
  }
  syncCurrentSessionBranch();
  cloudSyncing.value = true;
  try {
    const saved = await saveSessionState(
      activeSession.value as unknown as SessionState,
      authContext(),
    );
    const normalized = normalizeRemoteSession(saved);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    await refreshCostSummary();
    ElMessage.success('当前会话已保存到云端');
  } catch (error) {
    const message = error instanceof Error ? error.message : '云端保存失败';
    ElMessage.error(message);
  } finally {
    cloudSyncing.value = false;
  }
}

function getSession(sessionId: string): SessionRecord | undefined {
  return sessions.value.find((item) => item.id === sessionId);
}

function getBranch(session: SessionRecord, branchId: string): SessionBranch | undefined {
  return session.branches.find((branch) => branch.id === branchId);
}

function syncCurrentSessionBranch(): void {
  const session = getSession(activeSessionId.value);
  if (!session) {
    return;
  }

  const branch = getBranch(session, session.activeBranchId);
  if (!branch) {
    return;
  }

  session.modelProfile = modelProfile.value;
  session.streaming = streaming.value;

  branch.messages = [...messages.value];
  branch.traceSteps = [...traceSteps.value];
  branch.updatedAt = Date.now();

  const firstUser = branch.messages.find((item) => item.role === 'user');
  if (firstUser?.content?.trim()) {
    branch.title = deriveTitle(firstUser.content);
    session.title = deriveTitle(firstUser.content);
  }

  session.updatedAt = Date.now();
}

function loadSession(sessionId: string): void {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  activeSessionId.value = session.id;
  chatId.value = session.id;
  modelProfile.value = session.modelProfile;
  streaming.value = session.streaming;

  const branch = getBranch(session, session.activeBranchId) ?? session.branches[0];
  if (!branch) {
    const rootBranch = createRootBranch();
    session.branches = [rootBranch];
    session.activeBranchId = rootBranch.id;
    messages.value = [...rootBranch.messages].map((message) =>
      message.role === 'assistant' && message.state && message.state !== 'done'
        ? { ...message, state: 'done' }
        : message,
    );
    traceSteps.value = [...rootBranch.traceSteps];
  } else {
    messages.value = [...branch.messages].map((message) =>
      message.role === 'assistant' && message.state && message.state !== 'done'
        ? { ...message, state: 'done' }
        : message,
    );
    traceSteps.value = [...branch.traceSteps];
  }

  messageHeights.value = {};
  prompt.value = '';
  editingMessageId.value = null;
  editingMessageDraft.value = '';
  void scrollToBottom(true);
}

function switchSession(sessionId: string): void {
  if (isMobileViewport()) {
    sidebarOpen.value = false;
  }
  if (sessionId === activeSessionId.value) {
    return;
  }

  syncCurrentSessionBranch();
  loadSession(sessionId);
  persistState();
}

function createAndSwitchSession(): void {
  syncCurrentSessionBranch();
  const session = createSession();
  sessions.value.unshift(session);
  loadSession(session.id);
  persistState();
}

function removeSession(sessionId: string): void {
  if (sessions.value.length <= 1) {
    ElMessage.warning('至少保留一个会话');
    return;
  }

  syncCurrentSessionBranch();
  const filtered = sessions.value.filter((item) => item.id !== sessionId);
  sessions.value = filtered;

  if (activeSessionId.value === sessionId) {
    const next = filtered.find((item) => !item.archived) ?? filtered[0];
    loadSession(next.id);
  }

  persistState();
}

async function toggleSessionPin(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }
  session.pinned = !session.pinned;
  session.updatedAt = Date.now();
  persistState();
  if (canUseRemoteSync.value) {
    try {
      await setSessionPinned(sessionId, session.pinned, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话置顶同步失败';
      ElMessage.error(message);
    }
  }
}

async function toggleSessionArchive(sessionId: string): Promise<void> {
  const session = getSession(sessionId);
  if (!session) {
    return;
  }

  session.archived = !session.archived;
  session.updatedAt = Date.now();

  if (session.archived && !showArchivedSessions.value && activeSessionId.value === sessionId) {
    const next =
      sessions.value.find((item) => item.id !== sessionId && !item.archived) ??
      sessions.value.find((item) => item.id !== sessionId) ??
      createSession();

    if (!sessions.value.find((item) => item.id === next.id)) {
      sessions.value.unshift(next);
    }

    loadSession(next.id);
  }

  persistState();
  if (canUseRemoteSync.value) {
    try {
      await setSessionArchived(sessionId, session.archived, authContext());
    } catch (error) {
      const message = error instanceof Error ? error.message : '会话归档同步失败';
      ElMessage.error(message);
    }
  }
}

function createWorkspace(): void {
  const value = workspaceDraft.value.trim().toLowerCase();
  if (!value) {
    return;
  }

  activeWorkspaceId.value = value;
  workspaceFilter.value = value;
  workspaceDraft.value = '';
  persistState();
}

function switchBranch(branchId: string): void {
  const session = activeSession.value;
  if (session.activeBranchId === branchId) {
    return;
  }

  syncCurrentSessionBranch();
  session.activeBranchId = branchId;
  loadSession(session.id);
  persistState();
}

function forkBranch(
  title: string,
  baseMessages: ChatMessage[],
  parentBranchId: string | null,
  parentMessageId: string | null,
): SessionBranch {
  return {
    id: createBranchId(),
    title,
    parentBranchId,
    parentMessageId,
    updatedAt: Date.now(),
    messages: [...baseMessages],
    traceSteps: [],
  };
}

function forkFromCurrent(): void {
  const session = activeSession.value;
  const current = activeBranch.value;

  const branch = forkBranch(`${current.title} · fork`, [...messages.value], current.id, null);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);
  persistState();
  ElMessage.success('已创建分支');
}

async function compareWithParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可对比');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支对比');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await compareSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
      },
      authContext(),
    );
    ElMessage.success(
      `对比完成：公共 ${result.commonMessageCount}，当前独有 ${result.sourceOnlyCount}，父分支独有 ${result.targetOnlyCount}`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支对比失败';
    ElMessage.error(message);
  }
}

async function mergeIntoParent(): Promise<void> {
  const current = activeBranch.value;
  if (!current?.parentBranchId) {
    ElMessage.warning('当前分支没有父分支可合并');
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再执行云端分支合并');
    return;
  }
  syncCurrentSessionBranch();
  try {
    await syncActiveSessionToCloud();
    const result = await mergeSessionBranches(
      activeSession.value.id,
      {
        sourceBranchId: current.id,
        targetBranchId: current.parentBranchId,
        title: `${current.title} -> ${current.parentBranchId} merge`,
      },
      authContext(),
    );
    const normalized = normalizeRemoteSession(result.session);
    const index = sessions.value.findIndex((item) => item.id === normalized.id);
    if (index >= 0) {
      sessions.value[index] = normalized;
    } else {
      sessions.value.unshift(normalized);
    }
    loadSession(normalized.id);
    persistState();
    ElMessage.success(`合并完成，分支消息数 ${result.mergedMessageCount}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : '分支合并失败';
    ElMessage.error(message);
  }
}

function startEditMessage(message: ChatMessage): void {
  if (message.role !== 'user') {
    return;
  }

  editingMessageId.value = message.id;
  editingMessageDraft.value = message.content;
}

function cancelEditMessage(): void {
  editingMessageId.value = null;
  editingMessageDraft.value = '';
}

async function submitEditAndResend(messageIndex: number, messageId: string): Promise<void> {
  const question = editingMessageDraft.value.trim();
  if (!question) {
    return;
  }

  if (sending.value) {
    ElMessage.warning('请等待当前请求完成');
    return;
  }

  syncCurrentSessionBranch();

  const session = activeSession.value;
  const current = activeBranch.value;
  const baseMessages = messages.value.slice(0, Math.max(0, messageIndex));
  const branch = forkBranch(`${deriveTitle(question)} · edit`, baseMessages, current.id, messageId);

  session.branches.unshift(branch);
  session.activeBranchId = branch.id;
  loadSession(session.id);

  cancelEditMessage();
  persistState();

  await ask(question, true);
}

function upsertTrace(step: ReactTraceStep): void {
  const index = traceSteps.value.findIndex((item) => item.step === step.step);
  if (index >= 0) {
    traceSteps.value[index] = step;
  } else {
    traceSteps.value.push(step);
    traceSteps.value.sort((a, b) => a.step - b.step);
  }
}

function updateViewport(): void {
  viewportHeight.value = messageContainer.value?.clientHeight ?? 0;
  isMobile.value = isMobileViewport();
}

function onMessageScroll(): void {
  const element = messageContainer.value;
  if (!element) {
    return;
  }
  scrollTop.value = element.scrollTop;
}

function syncMessageHeight(messageId: string, height: number): void {
  if (height <= 0) {
    return;
  }

  const current = messageHeights.value[messageId] ?? 0;
  if (Math.abs(current - height) <= 1) {
    return;
  }

  messageHeights.value = {
    ...messageHeights.value,
    [messageId]: height,
  };
}

function setMessageRowRef(messageId: string, element: HTMLElement | null): void {
  const previous = messageRowElements.get(messageId);
  if (previous && previous !== element && resizeObserver) {
    resizeObserver.unobserve(previous);
    messageRowElements.delete(messageId);
  }

  if (!element) {
    return;
  }

  messageRowElements.set(messageId, element);
  syncMessageHeight(messageId, Math.ceil(element.getBoundingClientRect().height));
  if (resizeObserver) {
    resizeObserver.observe(element);
  }
}

async function scrollToBottom(force = false): Promise<void> {
  await nextTick();
  const element = messageContainer.value;
  if (!element) {
    return;
  }

  const remaining = element.scrollHeight - element.scrollTop - element.clientHeight;
  if (force || remaining < 180 || sending.value) {
    element.scrollTop = element.scrollHeight;
    scrollTop.value = element.scrollTop;
  }
}

async function handleMarkdownClick(event: MouseEvent): Promise<void> {
  const target = event.target as HTMLElement | null;
  const button = target?.closest('.copy-code-btn') as HTMLElement | null;
  if (!button) {
    return;
  }

  const payload = button.getAttribute('data-code');
  if (!payload) {
    return;
  }

  try {
    const raw = fromBase64(payload);
    await navigator.clipboard.writeText(raw);
    ElMessage.success('代码已复制');
  } catch {
    ElMessage.error('代码复制失败');
  }
}

async function copyMessage(content: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(content);
    ElMessage.success('已复制');
  } catch {
    ElMessage.error('复制失败');
  }
}

function parseCitation(citation: string): { source: string; chunk: string } | null {
  const matched = citation.match(/source=([^,]+),\s*chunk=(.+)$/i);
  if (!matched) {
    return null;
  }
  return {
    source: matched[1].trim(),
    chunk: matched[2].trim(),
  };
}

function openCitation(citation: string): void {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
  const target = parseCitation(citation);
  const url = `${base}/ai/pdf/file/${encodeURIComponent(chatId.value)}${
    target
      ? `?source=${encodeURIComponent(target.source)}&chunk=${encodeURIComponent(target.chunk)}`
      : ''
  }`;
  window.open(url, '_blank', 'noopener,noreferrer');
}

function findPreviousUserQuestion(index: number): string {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      return candidate.content.trim();
    }
  }
  return '';
}

async function rateAnswer(index: number, message: ChatMessage, rating: number): Promise<void> {
  if (message.role !== 'assistant') {
    return;
  }
  if (!canUseRemoteSync.value) {
    ElMessage.warning('请先完成鉴权后再提交反馈');
    return;
  }
  if (answerFeedbackMap.value[message.id]) {
    ElMessage.info('该回答已评分');
    return;
  }
  answerFeedbackLoading.value = {
    ...answerFeedbackLoading.value,
    [message.id]: true,
  };
  try {
    await submitAnswerFeedback(
      {
        chatId: chatId.value,
        sessionId: activeSession.value.id,
        branchId: activeBranch.value.id,
        messageId: message.id,
        rating,
        question: findPreviousUserQuestion(index),
        answer: message.content,
        comment: rating >= 4 ? '回答有帮助' : '回答需要改进',
      },
      authContext(),
    );
    answerFeedbackMap.value = {
      ...answerFeedbackMap.value,
      [message.id]: rating,
    };
    ElMessage.success('反馈已提交并回灌评测集');
  } catch (error) {
    const tip = error instanceof Error ? error.message : '反馈提交失败';
    ElMessage.error(tip);
  } finally {
    answerFeedbackLoading.value = {
      ...answerFeedbackLoading.value,
      [message.id]: false,
    };
  }
}

function stopGenerating(): void {
  currentAbortController.value?.abort();
}

function clearConversation(): void {
  messages.value = [createMessage('assistant', '会话已重置。你可以继续发问。')];
  traceSteps.value = [];
  answerFeedbackMap.value = {};
  answerFeedbackLoading.value = {};
  prompt.value = '';
  streamPhase.value = 'idle';
  streamStatusDetail.value = '';
  syncCurrentSessionBranch();
  persistState();
}

const retriedAuth = ref(false);
/** 游客模式：从 token 的 subject 派生（guest-* 前缀），刷新后依然生效。 */
const sidebarOpen = ref(typeof window !== 'undefined' ? window.innerWidth > 900 : true);
const isMobileViewport = () => typeof window !== 'undefined' && window.innerWidth <= 900;
const isMobile = ref(isMobileViewport());
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
    await uploadPdf(activeSessionId.value, file);
    ElMessage.success('PDF 已提交解析，入库后即可就本文档提问');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败');
  } finally {
    pdfUploading.value = false;
  }
}
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
    await loadSessionsFromCloud();
  } catch (error) {
    const message = error instanceof Error ? error.message : 'token exchange failed';
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
    const message = error instanceof Error ? error.message : 'refresh failed';
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

function sanitizeMessageStates(): void {
  messages.value = messages.value.map((message) => ({
    ...message,
    state:
      message.state === 'pending' || message.state === 'streaming'
        ? 'done'
        : message.state || 'done',
  }));
}

async function ask(question: string, appendUser: boolean): Promise<void> {
  if (!question.trim() || sending.value) {
    return;
  }

  sanitizeMessageStates();

  const assistantMsg: ChatMessage = {
    ...createMessage('assistant', ''),
    citations: [],
    citationCards: [],
    evidence: [],
    matchedQuestion: question,
    state: 'pending',
  };

  if (appendUser) {
    messages.value.push(createMessage('user', question));
  }
  messages.value.push(assistantMsg);
  const assistantIndex = messages.value.length - 1;

  traceSteps.value = [];
  sending.value = true;
  isStreamingResponse.value = streaming.value;
  prompt.value = '';
  streamPhase.value = 'thinking';
  streamStatusDetail.value = '模型正在准备响应';

  syncCurrentSessionBranch();
  persistState();
  await scrollToBottom(true);

  const controller = new AbortController();
  currentAbortController.value = controller;

  try {
    if (streaming.value) {
      let streamError = '';
      await streamReactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        (event, payload) => {
          if (event === 'trace') {
            const step = payload as ReactTraceStep;
            upsertTrace(step);
            streamPhase.value = 'tool';
            streamStatusDetail.value = `调用工具: ${step.action}`;
            return;
          }

          if (event === 'token') {
            const tokenEvent = payload as ReactTokenEvent;
            messages.value[assistantIndex].state = 'streaming';
            messages.value[assistantIndex].content += tokenEvent.token ?? '';
            streamPhase.value = 'streaming';
            streamStatusDetail.value = '正在生成文本';
            void scrollToBottom();
            return;
          }

          if (event === 'done') {
            const done = payload as ReactChatResponse & {
              citationCards?: ChatCitationCard[];
              retrievalTrace?: RetrievalTrace;
            };
            if (done.trace?.length) {
              traceSteps.value = done.trace;
            }
            if (done.answer?.trim()) {
              messages.value[assistantIndex].content = done.answer;
            }
            messages.value[assistantIndex].citations = Array.isArray(done.citations)
              ? done.citations.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].citationCards = Array.isArray(done.citationCards)
              ? done.citationCards.filter((card) => card && card.file && card.snippet)
              : [];
            messages.value[assistantIndex].retrievalTrace = done.retrievalTrace;
            messages.value[assistantIndex].evidence = Array.isArray(done.evidence)
              ? done.evidence.map((item) => String(item).trim()).filter(Boolean)
              : [];
            messages.value[assistantIndex].state = 'done';
            streamPhase.value = 'done';
            streamStatusDetail.value = '响应已完成';
            return;
          }

          if (event === 'error') {
            const err = payload as ReactErrorEvent;
            streamError = err.message || 'stream error';
          }
        },
        controller.signal,
      );

      if (streamError) {
        throw new Error(streamError);
      }
    } else {
      const result = await reactChat(
        {
          prompt: question,
          chatId: chatId.value,
          modelProfile: modelProfile.value,
        },
        authContext(),
        controller.signal,
      );
      traceSteps.value = result.trace ?? [];
      messages.value[assistantIndex].content = result.answer || '模型没有返回内容';
      const withCards = result as ReactChatResponse & {
        citationCards?: ChatCitationCard[];
        retrievalTrace?: RetrievalTrace;
      };
      messages.value[assistantIndex].citations = Array.isArray(result.citations)
        ? result.citations.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].citationCards = Array.isArray(withCards.citationCards)
        ? withCards.citationCards.filter((card) => card && card.file && card.snippet)
        : [];
      messages.value[assistantIndex].evidence = Array.isArray(result.evidence)
        ? result.evidence.map((item) => String(item).trim()).filter(Boolean)
        : [];
      messages.value[assistantIndex].retrievalTrace = withCards.retrievalTrace;
      messages.value[assistantIndex].state = 'done';
      streamPhase.value = 'done';
      streamStatusDetail.value = '响应已完成';
      retriedAuth.value = false;
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      ElMessage.info('已停止输出');
      if (!messages.value[assistantIndex].content.trim()) {
        messages.value[assistantIndex].content = '输出已手动停止。';
      }
      messages.value[assistantIndex].state = 'stopped';
      streamPhase.value = 'stopped';
      streamStatusDetail.value = '你手动停止了本次输出';
    } else {
      const message = error instanceof Error ? error.message : 'request failed';
      // 凭证过期：清 token → 游客自动登录 → 自动重试一次（对用户无感）
      if (/\b401\b/.test(message) && !retriedAuth.value) {
        retriedAuth.value = true;
        if (await ensureCredential()) {
          ElMessage.info('登录态已过期，已自动恢复，正在重试');
          messages.value.splice(assistantIndex, 1);
          sending.value = false;
          isStreamingResponse.value = false;
          currentAbortController.value = null;
          await ask(question, false);
          return;
        }
      }
      messages.value[assistantIndex].content = `请求失败：${message}`;
      messages.value[assistantIndex].state = 'error';
      streamPhase.value = 'error';
      streamStatusDetail.value = message;
      ElMessage.error(message);
    }
  } finally {
    sending.value = false;
    isStreamingResponse.value = false;
    currentAbortController.value = null;

    syncCurrentSessionBranch();
    persistState();
    await scrollToBottom(true);

    if (
      streamPhase.value === 'done' ||
      streamPhase.value === 'error' ||
      streamPhase.value === 'stopped'
    ) {
      scheduleStreamReset();
    }
  }
}

async function send(): Promise<void> {
  const question = prompt.value.trim();
  await ask(question, true);
}

async function regenerateFrom(assistantIndex: number): Promise<void> {
  for (let i = assistantIndex - 1; i >= 0; i -= 1) {
    const candidate = messages.value[i];
    if (candidate.role === 'user' && candidate.content.trim()) {
      syncCurrentSessionBranch();
      const session = activeSession.value;
      const current = activeBranch.value;
      const baseMessages = messages.value.slice(0, i);
      const branch = forkBranch(
        `${deriveTitle(candidate.content)} · retry`,
        baseMessages,
        current.id,
        candidate.id,
      );
      session.branches.unshift(branch);
      session.activeBranchId = branch.id;
      loadSession(session.id);
      persistState();
      await ask(candidate.content, true);
      return;
    }
  }

  ElMessage.warning('没有找到可重试的用户问题');
}

watch(
  darkMode,
  () => {
    document.documentElement.classList.toggle('dark', darkMode.value);
    persistState();
  },
  { immediate: true },
);

watch([modelProfile, streaming, workspaceFilter, showArchivedSessions, sessionSearch], () => {
  syncCurrentSessionBranch();
  persistState();
});

let syncPersistTimer: number | undefined;
let lastSyncPersistAt = 0;

/** 流式期间 messages 高频变化：sync+localStorage 全量序列化必须节流（400ms 尾随），否则手机端主线程被打满。 */
function scheduleSyncPersist(): void {
  const now = Date.now();
  if (now - lastSyncPersistAt >= 400) {
    lastSyncPersistAt = now;
    syncCurrentSessionBranch();
    persistState();
    return;
  }
  if (syncPersistTimer !== undefined) {
    return;
  }
  syncPersistTimer = window.setTimeout(() => {
    syncPersistTimer = undefined;
    lastSyncPersistAt = Date.now();
    syncCurrentSessionBranch();
    persistState();
  }, 400);
}

watch(
  [messages, traceSteps],
  () => {
    scheduleSyncPersist();
  },
  { deep: true },
);

watch([apiKeyInput, tenantInput, token, refreshToken], () => {
  persistState();
  if (canUseRemoteSync.value) {
    void refreshCostSummary();
  } else {
    costSummary.value = null;
  }
});

watch(
  messages,
  () => {
    const idSet = new Set(messages.value.map((message) => message.id));
    const filtered: Record<string, number> = {};
    Object.entries(messageHeights.value).forEach(([id, height]) => {
      if (idSet.has(id)) {
        filtered[id] = height;
      }
    });
    messageHeights.value = filtered;
    void scrollToBottom();
  },
  { deep: false },
);

onMounted(() => {
  // 先确保证据就绪（游客/已存 Key），再发起会话与评测等带鉴权的初始请求，避免开机 401
  void (async () => {
    if (!token.value) {
      await ensureCredential();
    }
    loadSession(activeSessionId.value);
    // 游客无 cost/memory 权限，跳过对应初始请求避免 401
    if (!guestMode.value) {
      void loadMemoryHint();
      void refreshCostSummary();
    }
  })();
  updateViewport();

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver((entries) => {
      entries.forEach((entry) => {
        const element = entry.target as HTMLElement;
        const messageId = element.dataset.msgId;
        if (!messageId) {
          return;
        }
        syncMessageHeight(messageId, Math.ceil(entry.contentRect.height));
      });
    });
  }

  window.addEventListener('resize', updateViewport);
  window.setTimeout(() => {
    hydrating.value = false;
    void scrollToBottom(true);
  }, 220);

  if (activeView.value === 'evaluation') {
    void loadEvalDatasets();
  }

  void scrollToBottom(true);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateViewport);

  if (resizeObserver) {
    messageRowElements.forEach((element) => {
      resizeObserver?.unobserve(element);
    });
    resizeObserver.disconnect();
    resizeObserver = null;
  }

  if (streamResetTimer) {
    window.clearTimeout(streamResetTimer);
    streamResetTimer = null;
  }
});
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  color: var(--ui-text);
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border-right: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  backdrop-filter: blur(12px);
}

.brand-block {
  padding: 4px 2px;
}

.eyebrow {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

h1 {
  margin: 8px 0 0;
  font-size: 25px;
  line-height: 1.1;
  letter-spacing: -0.02em;
}

.brand-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--ui-muted);
}

.new-chat-btn {
  border: 1px solid rgba(14, 116, 144, 0.35);
  background: linear-gradient(150deg, rgba(14, 116, 144, 0.2), rgba(15, 118, 110, 0.14));
  color: var(--ui-text);
  border-radius: 12px;
  padding: 10px 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}

.new-chat-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(14, 116, 144, 0.18);
}

.console-nav {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  padding: 4px;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
}

.console-nav button {
  border: 0;
  border-radius: 8px;
  padding: 8px 10px;
  color: var(--ui-muted);
  background: transparent;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.console-nav button.active {
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-card) 94%, transparent);
  box-shadow: inset 0 0 0 1px rgba(14, 116, 144, 0.22);
}

.session-tools {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.cloud-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.tool-select {
  min-width: 0;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.section-label {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

.section-meta {
  font-size: 12px;
  color: var(--ui-muted);
}

.session-panel,
.branch-panel {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 84%, transparent);
}

.session-list,
.branch-list {
  max-height: 214px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-item {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  background: color-mix(in oklab, var(--ui-card) 76%, transparent);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: border-color 160ms ease;
}

.session-item:hover,
.session-item.active {
  border-color: rgba(14, 116, 144, 0.45);
}

.session-content {
  min-width: 0;
}

.session-title-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.session-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta-row {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ui-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.session-actions button,
.branch-head-actions button {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 3px 8px;
  font-size: 11px;
  cursor: pointer;
}

.session-actions .danger {
  color: #dc2626;
}

.session-empty {
  font-size: 12px;
  color: var(--ui-muted);
  padding: 6px;
}

.branch-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.branch-item {
  position: relative;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: color-mix(in oklab, var(--ui-card) 80%, transparent);
  cursor: pointer;
}

.branch-item.active {
  border-color: rgba(14, 116, 144, 0.45);
}

.branch-line {
  width: 10px;
  height: 1px;
  background: var(--ui-muted);
}

.branch-content {
  min-width: 0;
}

.branch-content p,
.branch-content small {
  margin: 0;
}

.branch-content p {
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-content small {
  color: var(--ui-muted);
}

.ops-panel {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  overflow: hidden;
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
}

.ops-panel summary {
  list-style: none;
  cursor: pointer;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 600;
  background: color-mix(in oklab, var(--ui-card) 80%, transparent);
}

.ops-panel summary::-webkit-details-marker {
  display: none;
}

.ops-body {
  padding: 12px;
}

.auth-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.full-width {
  width: 100%;
}

.workspace {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.workspace-head {
  position: sticky;
  top: 0;
  z-index: 8;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 84%, transparent);
  backdrop-filter: blur(10px);
}

.workspace-kicker {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--ui-muted);
}

h2 {
  margin: 8px 0 0;
  font-size: 22px;
  line-height: 1.22;
}

.workspace-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ui-muted);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.workspace-select {
  width: 130px;
}

.workspace-input {
  width: 130px;
}

.stream-detail {
  font-size: 12px;
  color: var(--ui-muted);
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 14px 0;
}

.hydration-skeleton {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px;
  display: grid;
  gap: 10px;
}

.skeleton-line,
.skeleton-bubble {
  border-radius: 10px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.18),
    rgba(148, 163, 184, 0.34),
    rgba(148, 163, 184, 0.18)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.skeleton-line {
  height: 12px;
}

.skeleton-line.lg {
  width: 52%;
}

.skeleton-line.short {
  width: 36%;
}

.skeleton-bubble {
  height: 84px;
}

.skeleton-bubble.alt {
  width: 76%;
  justify-self: end;
}

.welcome-block {
  max-width: 930px;
  margin: 0 auto 12px;
  padding: 0 20px;
}

.welcome-block h3 {
  margin: 0;
  font-size: 28px;
  line-height: 1.16;
}

.welcome-block p {
  margin: 8px 0 0;
  color: var(--ui-muted);
}

.welcome-prompts {
  margin-top: 14px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.welcome-prompts button,
.quick-prompts button,
.message-actions button {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-panel) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  cursor: pointer;
}

.virtual-spacer {
  width: 100%;
  pointer-events: none;
}

.message-row {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 18px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 12px;
  align-items: flex-start;
}

.message-row.user {
  grid-template-columns: minmax(0, 1fr) 38px;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  background: linear-gradient(150deg, #0f766e, #0369a1);
  color: #f8fafc;
}

.message-row.user .avatar {
  grid-column: 2;
  background: linear-gradient(150deg, #1d4ed8, #4338ca);
}

.bubble-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-row.user .bubble-wrap {
  align-items: flex-end;
}

.bubble-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--ui-muted);
  font-size: 12px;
}

.status-dot {
  color: var(--ui-accent);
}

.bubble {
  width: min(100%, 860px);
  border-radius: 14px;
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 86%, transparent);
  padding: 12px 14px;
}

.message-row.assistant .bubble {
  border: none;
  background: transparent;
  padding: 2px 0;
}

.message-row.user .bubble {
  background: linear-gradient(150deg, rgba(29, 78, 216, 0.15), rgba(67, 56, 202, 0.12));
}

.assistant-skeleton {
  display: grid;
  gap: 8px;
}

.assistant-skeleton div {
  height: 12px;
  border-radius: 8px;
  background: linear-gradient(
    90deg,
    rgba(148, 163, 184, 0.16),
    rgba(148, 163, 184, 0.3),
    rgba(148, 163, 184, 0.16)
  );
  background-size: 220% 100%;
  animation: shimmer 1.3s linear infinite;
}

.assistant-skeleton div:nth-child(3) {
  width: 65%;
}

.edit-box {
  display: grid;
  gap: 8px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.plain {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.6;
}

.message-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.citation-panel,
.evidence-panel {
  margin-top: 10px;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 8px 10px;
  background: color-mix(in oklab, var(--ui-panel) 86%, transparent);
}

.citation-title {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ui-muted);
}

.citation-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.citation-chip {
  border: 1px solid var(--ui-border);
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
  color: var(--ui-text);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  text-align: left;
}

/* ── 校园规章主题设计令牌（styles.css 不在改动白名单，挂 .app-shell 等效 :root） ── */
.app-shell {
  font-family: -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  --color-primary: #1e3a8a;
  --color-accent: #2563eb;
  --color-bg: #f8fafc;
  --color-surface: #ffffff;
  --color-text: #0f172a;
  --color-text-sub: #64748b;
  --color-border: #e2e8f0;
  --color-quote-bg: #f1f5f9;
  --radius-card: 10px;
  --radius-chip: 8px;
  --radius-input: 12px;
  --shadow-card: 0 1px 3px rgba(15, 23, 42, 0.06);
  --shadow-hover: 0 4px 12px rgba(15, 23, 42, 0.1);
}

/* ── 条款级引用卡片（spec-A4/A9） ── */
.citation-card-list {
  display: grid;
  gap: 8px;
}

.citation-card {
  background: var(--color-quote-bg);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-chip);
  padding: 12px 16px;
  box-shadow: var(--shadow-card);
}

.citation-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  background: transparent;
  border: none;
  padding: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  cursor: pointer;
  text-align: left;
}

.citation-card-index {
  font-variant-numeric: tabular-nums;
  color: var(--color-primary);
}

.citation-card-page {
  color: var(--color-text-sub);
  font-weight: 400;
}

.citation-card-caret {
  margin-left: auto;
  color: var(--color-text-sub);
}

.citation-card-snippet {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-text);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.citation-card-snippet.expanded {
  display: block;
  overflow: visible;
}

.citation-card-snippet mark {
  background: #fef08a;
  color: inherit;
  padding: 0 2px;
  border-radius: 4px;
}

.citation-card-actions {
  margin-top: 8px;
  text-align: right;
}

.citation-card-link {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 12px;
  color: var(--color-accent);
  cursor: pointer;
}

.citation-card-link:hover {
  text-decoration: underline;
}

/* ── 兜底提示条（spec-A7：黄底，不用红） ── */
.no-citation-fallback {
  margin-top: 8px;
  background: #fffbeb;
  border-left: 3px solid #fde68a;
  color: #92400e;
  border-radius: var(--radius-chip);
  padding: 8px 16px;
  font-size: 13px;
  line-height: 1.6;
}

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

/* ── 检索轨迹行（spec-A5：壳，标「轨迹未接入」） ── */
.retrieval-trace-row {
  margin-top: 8px;
}

.retrieval-trace-toggle {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 13px;
  color: var(--color-text-sub);
  cursor: pointer;
  font-variant-numeric: tabular-nums;
}

.retrieval-trace-detail {
  margin-top: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-chip);
  background: var(--color-surface);
  padding: 8px 16px;
}

.retrieval-trace-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-sub);
}

.retrieval-trace-detail table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.retrieval-trace-detail th,
.retrieval-trace-detail td {
  border-bottom: 1px solid var(--color-border);
  padding: 4px 8px;
  text-align: left;
  font-variant-numeric: tabular-nums;
}

.retrieval-trace-detail th {
  color: var(--color-text-sub);
  font-weight: 600;
}

/* ── 空状态 chip（spec-A3/A9） ── */
.welcome-prompts button {
  transition:
    transform 150ms ease,
    box-shadow 150ms ease;
}

.welcome-prompts button:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-hover);
}

.evidence-panel ul {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 4px;
  color: var(--ui-muted);
  font-size: 12px;
}

/* ── Agent Trace Timeline ─────────────────────────── */
.trace-timeline-panel {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-panel) 92%, transparent);
  border: 1px solid var(--ui-border);
}
.trace-timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.trace-timeline-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--ui-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.trace-timeline-meta {
  font-size: 11px;
  color: var(--ui-muted);
}
.trace-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.trace-timeline-step {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  min-height: 48px;
}
.trace-timeline-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}
.trace-node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #6366f1;
  border: 2px solid var(--ui-card);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.25);
  flex-shrink: 0;
  z-index: 1;
}
.trace-action-tool_call .trace-node {
  background: #8b5cf6;
  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.25);
}
.trace-action-finish .trace-node {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.25);
}
.trace-action-error .trace-node {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.25);
}
.trace-connector {
  width: 2px;
  flex: 1;
  background: linear-gradient(180deg, rgba(99, 102, 241, 0.4), rgba(99, 102, 241, 0.08));
  min-height: 20px;
}
.trace-timeline-content {
  padding-bottom: 14px;
}
.trace-last .trace-timeline-content {
  padding-bottom: 0;
}
.trace-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.trace-step-label {
  font-weight: 700;
  font-size: 12px;
  color: var(--ui-text);
}
.trace-action-badge {
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #fff;
  background: #6366f1;
}
.badge-thought {
  background: #3b82f6;
}
.badge-tool_call {
  background: #8b5cf6;
}
.badge-finish {
  background: #10b981;
}
.badge-error {
  background: #f59e0b;
}
.trace-field-label {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ui-muted);
  margin-bottom: 3px;
  display: block;
}
.trace-thought-block p {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ui-text);
}
.trace-input-block pre,
.trace-obs-block pre {
  margin: 4px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 90%, transparent);
  font-size: 11px;
  overflow-x: auto;
  max-height: 100px;
  border: 1px solid var(--ui-border);
}
.trace-obs-block summary {
  cursor: pointer;
  padding: 2px 0;
}
.trace-retrieval-lanes {
  margin-top: 8px;
  padding: 8px;
  border-radius: 8px;
  background: color-mix(in oklab, var(--ui-bg, #f8fafc) 80%, transparent);
  border: 1px solid var(--ui-border);
}
.retrieval-bar {
  display: flex;
  border-radius: 6px;
  overflow: hidden;
  margin-top: 6px;
  height: 24px;
  font-size: 10px;
  font-weight: 700;
}
.retrieval-lane {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  padding: 0 4px;
  transition: filter 0.2s ease;
}
.retrieval-lane:hover {
  filter: brightness(1.15);
}
.retrieval-lane.vector {
  background: #6366f1;
}
.retrieval-lane.keyword {
  background: #0ea5e9;
}
.retrieval-lane.graph {
  background: #f59e0b;
}
.retrieval-lane.web {
  background: #10b981;
}

.thinking {
  max-width: 930px;
  margin: 0 auto;
  padding: 0 20px 10px 70px;
  color: var(--ui-muted);
  font-size: 13px;
}

.evaluation-page {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  gap: 14px;
  padding: 14px;
}

.eval-side-panel,
.eval-main-panel {
  min-height: 0;
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  background: color-mix(in oklab, var(--ui-card) 88%, transparent);
}

.eval-side-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
}

.eval-panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.eval-panel-head strong {
  display: block;
  margin-top: 4px;
  font-size: 22px;
}

.eval-dataset-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.eval-dataset-list button {
  width: 100%;
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 9px 10px;
  text-align: left;
  color: var(--ui-text);
  background: color-mix(in oklab, var(--ui-panel) 82%, transparent);
  cursor: pointer;
}

.eval-dataset-list button.active {
  border-color: rgba(15, 118, 110, 0.55);
  box-shadow: inset 0 0 0 1px rgba(15, 118, 110, 0.28);
}

.eval-dataset-list span,
.eval-dataset-list small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-dataset-list span {
  font-size: 13px;
  font-weight: 700;
}

.eval-dataset-list small {
  margin-top: 4px;
  color: var(--ui-muted);
}

.eval-create-panel {
  display: grid;
  gap: 8px;
}

.eval-json-input :deep(.el-textarea__inner) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}

.eval-main-panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  padding: 12px;
}

.eval-score-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(112px, 1fr));
  gap: 8px;
}

.eval-metric-card {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 80%, transparent);
  min-width: 0;
}

.eval-metric-card span,
.eval-metric-card small {
  display: block;
  font-size: 11px;
  color: var(--ui-muted);
}

.eval-metric-card strong {
  display: block;
  margin: 7px 0 4px;
  font-size: 22px;
  line-height: 1;
}

.eval-metric-card .good {
  color: #047857;
}

.eval-metric-card .bad {
  color: #dc2626;
}

.eval-metric-card .neutral {
  color: var(--ui-muted);
}

.eval-run-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.eval-run-summary {
  border: 1px solid var(--ui-border);
  border-radius: 10px;
  padding: 10px;
  background: color-mix(in oklab, var(--ui-panel) 76%, transparent);
}

.eval-run-summary.current {
  border-color: rgba(15, 118, 110, 0.45);
}

.eval-run-summary strong,
.eval-run-summary span {
  display: block;
  margin-top: 6px;
}

.eval-run-summary strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-run-summary span {
  font-size: 22px;
  font-weight: 800;
}

.eval-result-table {
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;
}

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

.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.composer-actions {
  display: flex;
  gap: 8px;
}

.markdown :deep(p) {
  margin: 0 0 10px;
  line-height: 1.7;
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 10px;
  padding-left: 20px;
}

.markdown :deep(code:not(.hljs)) {
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  background: rgba(15, 23, 42, 0.1);
  border-radius: 4px;
  padding: 2px 5px;
}

.markdown :deep(.code-block) {
  border: 1px solid var(--ui-border);
  border-radius: 12px;
  overflow: hidden;
  background: #0b1220;
  color: #e6edf7;
}

.markdown :deep(.code-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: #111b2f;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.markdown :deep(.code-lang) {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #9fb2cf;
}

.markdown :deep(.copy-code-btn) {
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 999px;
  padding: 4px 8px;
  font-size: 11px;
  background: rgba(15, 23, 42, 0.5);
  color: #e6edf7;
  cursor: pointer;
}

.markdown :deep(pre) {
  margin: 0;
  padding: 0;
  overflow-x: auto;
}

.markdown :deep(code.hljs) {
  display: block;
  padding: 10px 0;
  background: transparent;
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}

.markdown :deep(.code-line) {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 10px;
  padding: 0 12px;
  white-space: pre;
}

.markdown :deep(.line-no) {
  user-select: none;
  color: rgba(148, 163, 184, 0.7);
  text-align: right;
}

.markdown :deep(.line-content) {
  min-width: 0;
}

.markdown :deep(.hljs-comment),
.markdown :deep(.hljs-quote) {
  color: #8aa0bf;
}

.markdown :deep(.hljs-keyword),
.markdown :deep(.hljs-selector-tag),
.markdown :deep(.hljs-subst) {
  color: #ff8fa3;
}

.markdown :deep(.hljs-string),
.markdown :deep(.hljs-doctag) {
  color: #8be9a8;
}

.markdown :deep(.hljs-title),
.markdown :deep(.hljs-section),
.markdown :deep(.hljs-selector-id) {
  color: #8fc7ff;
}

.markdown :deep(.hljs-number),
.markdown :deep(.hljs-literal) {
  color: #f6c177;
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

@keyframes shimmer {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -120% 0;
  }
}

@media (max-width: 1160px) {
  .app-shell {
    grid-template-columns: 300px minmax(0, 1fr);
  }

  .eval-score-strip {
    grid-template-columns: repeat(3, minmax(112px, 1fr));
  }
}

@media (max-width: 980px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .sidebar {
    border-right: none;
    border-bottom: 1px solid var(--ui-border);
  }

  .session-list,
  .branch-list {
    max-height: 170px;
  }

  .workspace-head {
    position: static;
  }

  .evaluation-page {
    grid-template-columns: 1fr;
  }

  .eval-dataset-list {
    max-height: 180px;
  }

  .composer-shell {
    position: static;
  }
}

@media (max-width: 680px) {
  .workspace-head {
    padding-left: 12px;
    padding-right: 12px;
  }

  .head-actions {
    justify-content: flex-start;
  }

  .workspace-select,
  .workspace-input {
    width: 120px;
  }

  .evaluation-page {
    padding: 12px;
  }

  .eval-score-strip,
  .eval-run-grid {
    grid-template-columns: 1fr;
  }

  .welcome-block,
  .message-row,
  .thinking,
  .hydration-skeleton {
    padding-left: 12px;
    padding-right: 12px;
  }

  .message-row {
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 8px;
  }

  .message-row.user {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .avatar {
    width: 32px;
    height: 32px;
    border-radius: 8px;
  }

  .thinking {
    padding-left: 52px;
  }

  .composer-footer {
    flex-direction: column;
    align-items: flex-start;
  }
}

/* ════════════════════════════════════════════════
   有章 · 学术风覆盖层（改版新增，位于最末以获得优先级）
   ════════════════════════════════════════════════ */

/* ── 整体骨架 ── */
.app-shell {
  height: 100vh;
  grid-template-columns: 264px minmax(0, 1fr);
  background: var(--ui-paper);
}

/* 流式中的纯文本渲染（完成后才转 markdown，避免逐 token 全量重渲染） */
.streaming-plain {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 游客模式标识 */
.guest-chip {
  --el-tag-font-size: 11px;
}

/* ── 侧栏 ── */
.sidebar {
  gap: 10px;
  padding: 16px 14px 10px;
  background: var(--ui-surface);
  border-right: 1px solid var(--ui-border-soft);
  backdrop-filter: none;
}

.brand-block h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ui-text);
}

.brand-block .brand-sub {
  margin: 3px 0 0;
  font-size: 10.5px;
  line-height: 1.4;
  color: var(--ui-muted);
}

.new-chat-btn {
  height: 38px;
  border: none;
  border-radius: 12px;
  background: var(--ui-accent);
  color: #fff;
  font-size: 13.5px;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: filter 0.15s ease;
}

.new-chat-btn:hover {
  filter: brightness(1.08);
}

.session-tools .el-input {
  --el-input-bg-color: var(--ui-paper);
}

.session-panel {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.session-item {
  border: none;
  border-radius: 10px;
  background: transparent;
  transition: background 0.12s ease;
}

.session-item:hover {
  background: var(--ui-paper);
}

.session-item.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

.session-item.active .session-title {
  color: var(--ui-accent);
  font-weight: 500;
}

.session-title {
  font-size: 13.5px;
}

.session-meta-row {
  font-size: 11px;
  color: var(--ui-muted);
}

.session-actions button {
  border: none;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 6px;
}

.session-actions button:hover {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

.session-actions button.danger:hover {
  color: var(--ui-danger);
}

/* 分支折叠面板 */
.branch-collapse {
  border-top: 1px solid var(--ui-border-soft);
  --el-collapse-border-color: transparent;
}

.branch-collapse .el-collapse-item__header {
  background: transparent;
  border-bottom: none;
  font-size: 12px;
  color: var(--ui-muted);
  height: 36px;
}

.branch-collapse-title {
  font-size: 12px;
  color: var(--ui-muted);
}

.branch-panel {
  padding: 0 2px 8px;
}

.branch-head-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.branch-item {
  border: none;
  border-radius: 8px;
}

.branch-item.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

/* 侧栏底部评测入口 */
.sidebar-foot {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--ui-border-soft);
}

.eval-entry {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 10px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12.5px;
  font-family: inherit;
  cursor: pointer;
  transition:
    color 0.12s ease,
    background 0.12s ease;
}

.eval-entry:hover {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

/* ── 主区顶栏 ── */
.workspace-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 28px;
  border-bottom: 1px solid var(--ui-border-soft);
  background: var(--ui-surface);
}

.workspace-head > div:first-of-type {
  flex: 1;
  min-width: 0;
}

.workspace-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.workspace-kicker {
  margin: 0 0 2px;
  font-size: 10.5px;
  letter-spacing: 0.1em;
  color: var(--ui-muted);
  text-transform: none;
}

.workspace-sub {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--ui-muted);
}

.stream-detail {
  font-size: 11.5px;
  color: var(--ui-muted);
}

/* ── 空会话提示（极简） ── */
.empty-hint {
  margin: 14vh auto 0;
  max-width: 930px;
  padding: 0 20px;
  text-align: center;
}

.empty-hint p {
  margin: 0;
  font-size: 12.5px;
  color: var(--ui-muted);
  opacity: 0.75;
}

/* ── 消息 ── */
.avatar {
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
  border: 1px solid var(--ui-accent-line);
  font-size: 12px;
  font-weight: 500;
}

.message-row.user .avatar {
  background: var(--ui-panel);
  color: var(--ui-muted);
  border-color: var(--ui-border-soft);
}

.bubble {
  border: 1px solid var(--ui-border-soft);
  border-radius: 14px;
  box-shadow: var(--ui-shadow);
  background: var(--ui-surface);
  padding: 12px 16px;
  font-size: 13.5px;
  line-height: 1.7;
}

.message-row.user .bubble {
  background: var(--ui-accent);
  border-color: var(--ui-accent);
  color: #fff;
  border-radius: 14px 14px 5px 14px;
  width: fit-content;
  max-width: 100%;
  margin-left: auto;
  text-align: left;
}

.bubble-meta {
  font-size: 11px;
  color: var(--ui-muted);
}

.message-actions button {
  border: none;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 7px;
}

.message-actions button:hover:not(:disabled) {
  color: var(--ui-accent);
  background: var(--ui-accent-soft);
}

/* 引用依据卡片：淡纸色 */
.citation-title {
  font-weight: 600;
  font-size: 12.5px;
  color: var(--ui-text);
}

.citation-card {
  background: color-mix(in oklab, var(--ui-panel) 70%, transparent);
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  padding: 12px 16px;
  box-shadow: none;
}

.citation-card-file {
  font-weight: 600;
}

/* 检索链路行 */
.retrieval-trace-row {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--ui-border-soft);
}

.retrieval-trace-toggle {
  border: none;
  background: none;
  color: var(--ui-muted);
  font-size: 11.5px;
  cursor: pointer;
  padding: 0;
  font-family: inherit;
}

.retrieval-trace-toggle:hover {
  color: var(--ui-accent);
}

/* 无依据兜底 */
.no-citation-fallback {
  border: 1px solid color-mix(in oklab, var(--ui-danger) 30%, transparent);
  background: color-mix(in oklab, var(--ui-danger) 6%, transparent);
  border-radius: 12px;
  font-size: 12.5px;
}

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

.quick-prompts {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.quick-prompts button {
  border: 1px solid var(--ui-border);
  border-radius: 999px;
  background: transparent;
  color: var(--ui-muted);
  font-size: 12px;
  font-family: inherit;
  padding: 4px 13px;
  cursor: pointer;
  transition:
    color 0.12s ease,
    border-color 0.12s ease,
    background 0.12s ease;
}

.quick-prompts button:hover {
  color: var(--ui-accent);
  border-color: var(--ui-accent-line);
  background: var(--ui-accent-soft);
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

/* ── 设置弹窗 ── */
.settings-section {
  padding: 4px 0 12px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--ui-border-soft);
}

.settings-section:last-of-type {
  border-bottom: none;
  margin-bottom: 0;
}

.settings-label {
  margin: 0 0 10px;
  font-weight: 600;
  font-size: 13px;
  color: var(--ui-text);
}

.workspace-manager {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.workspace-manager .workspace-select {
  flex: 1;
  min-width: 140px;
}

.workspace-manager .workspace-input {
  flex: 1;
  min-width: 120px;
}

.settings-dialog .cloud-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

/* ── 评测页 ── */
.eval-create-collapse {
  --el-collapse-border-color: transparent;
}

.eval-create-collapse .el-collapse-item__header {
  background: transparent;
  border-bottom: none;
  height: 34px;
}

.eval-side-panel {
  background: transparent;
  border-right: 1px solid var(--ui-border-soft);
}

.eval-dataset-list button {
  border: none;
  border-radius: 9px;
  background: transparent;
}

.eval-dataset-list button.active {
  background: var(--ui-accent-soft);
  box-shadow: none;
}

.eval-metric-card {
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  background: var(--ui-surface);
  box-shadow: none;
}

.eval-metric-card strong {
  font-variant-numeric: tabular-nums;
}

.eval-run-summary {
  border: 1px solid var(--ui-border-soft);
  border-radius: 12px;
  background: var(--ui-surface);
  box-shadow: none;
}

.eval-run-summary.current {
  border-color: var(--ui-accent-line);
}

.eval-result-table {
  --el-table-border-color: var(--ui-border-soft);
  --el-table-header-text-color: var(--ui-muted);
}

/* ════════ 响应式：侧栏抽屉 + 移动端适配 ════════ */

/* 侧栏开关按钮（顶栏） */
.sidebar-toggle {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--ui-muted);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.sidebar-toggle:hover {
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
}

/* 遮罩：仅移动端抽屉打开时显示 */
.sidebar-backdrop {
  display: none;
}

/* ── 桌面：侧栏可完全收起 ── */
@media (min-width: 901px) {
  .app-shell {
    transition: grid-template-columns 0.2s ease;
  }

  .app-shell.sidebar-closed {
    grid-template-columns: 0 minmax(0, 1fr);
  }

  .app-shell.sidebar-closed .sidebar {
    display: none;
  }
}

/* ── 移动端（≤900px）：侧栏变抽屉，整体瘦身 ── */
@media (max-width: 900px) {
  .app-shell {
    grid-template-columns: minmax(0, 1fr);
    display: block;
    position: relative;
  }

  .sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 60;
    width: min(78vw, 300px);
    transform: translateX(-102%);
    transition: transform 0.22s ease;
    box-shadow: 0 0 40px rgba(28, 43, 58, 0.18);
    overflow-y: auto;
  }

  .app-shell.sidebar-open .sidebar {
    transform: none;
  }

  .sidebar-backdrop {
    display: none;
  }

  .app-shell.sidebar-open ~ .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 55;
    background: rgba(28, 43, 58, 0.35);
    backdrop-filter: blur(1px);
  }

  /* 顶栏固定：滚动时保持可见（豆包/DeepSeek 式） */
  .workspace-head {
    position: sticky;
    top: 0;
    z-index: 50;
    padding: 10px 14px;
  }

  .workspace-kicker,
  .workspace-sub,
  .stream-detail {
    display: none;
  }

  .workspace-head h2 {
    font-size: 13.5px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 46vw;
  }

  .brand-sub {
    display: none;
  }

  .brand-block h1 {
    font-size: 14px;
  }

  /* 消息区：卡片自适应铺满 */
  .message-row,
  .thinking,
  .hydration-skeleton,
  .welcome-block,
  .empty-hint {
    max-width: 100%;
    padding: 0 12px 14px;
  }

  .bubble {
    padding: 11px 13px;
    border-radius: 12px;
  }

  .message-row.user .bubble {
    border-radius: 12px 12px 4px 12px;
  }

  /* 输入区收紧 */
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

  /* 评测页纵向堆叠 */
  .evaluation-page {
    flex-direction: column;
    overflow-y: auto;
  }

  .eval-side-panel {
    border-right: none;
    border-bottom: 1px solid var(--ui-border-soft);
  }
}
</style>
