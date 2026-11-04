# 缺陷审查报告：EvaluationPanel.vue

- **审查对象**：`frontend/src/components/EvaluationPanel.vue`（767 行，全文通读）
- **审查方式**：只读静态审查（环境无 node_modules，无法运行 vue-tsc/build，结论均来自源码交叉比对）；直接协作者已逐一核实——`api/client.ts:541-647`（六个评测函数：`encodeURIComponent` 编码、`parseJsonSafely` 解包、`formatHttpError`→`FriendlyError` 携带状态码）、`types/react.ts`（EvalDataset/EvalRun/EvalComparison/EvalMetricSummary）、`composables/useGuestRestriction.ts`（403 引导卡单例）、`utils/errors.ts`（`toUserMessage`/`errorStatus`，英文技术报错被过滤为 fallback）、`utils/session.ts`（shortId）、父组件 `App.vue:132-140`（**`v-show` 常驻**，状态跨视图保留；`@persist`/`v-model:selected-dataset-id` 接线）、`App.vue:66-86`（顶栏四个按钮的 loading/disabled 绑定）、`App.vue:400-405,502-505`（视图切换与挂载时的 `maybeLoadDatasets` 调用点）、后端 `evaluation/EvaluationController.java`（返回裸 VO 无 Result 包装，与前端类型吻合；`:57` 已有 `GET /runs/{runId}` 单运行查询端点）
- **审查日期**：2026-11-04
- **约束遵守**：未修改任何源码；本报告为唯一新增文件；未执行 git/npm install/build 操作
- **关联报告**：`reports/audit-MessageList.vue.md`（同批拆分组件）、`reports/audit-AnswerFeedbackService.java.md`（评测数据集的落库侧）

---

## 概览

该组件是评测工作台：数据集列表/JSON 创建、触发评测运行、基线对比指标卡、结果表格、报告导出。做对的部分：

- **无任何 `v-html`**，问题/用例全部文本插值，本组件无 XSS 面；
- **`v-model:selected-dataset-id` 的本地镜像模式正确**（`evalSelectedDatasetId` + watch 回同步，`:193-209`），注释解释了为何需要镜像（props 回传渲染时序），且双向不会成环；
- **统一错误出口** `handleEvalError`：游客 403 转 `reportGuestRestriction('检索评测')` 引导卡，其余走 `toUserMessage`（`:174-181`）；`errorStatus` 能从 `FriendlyError` 与旧式文案双通道识别 403；
- **请求路径参数全部 `encodeURIComponent`**（client.ts:580,603,619,635）；
- **JSON 导入容错充分**：支持顶层数组 / `{cases:[…]}` / OpenAPI `paths.*.cases` 三种形态，`normalizeEvalCase` 兼容 camelCase/snake_case，空 case 过滤 + 明确的中文报错（`:294-323`）；
- **指标卡 delta 方向语义正确**：`avgLatencyMs`/`failureRate` 传 `lowerIsBetter`，0 差值与符号处理无误（`:232-259`）；
- **与后端契约吻合**：Controller 返回裸 `EvalComparisonVO` 等 VO（无 Result 包装），前端直接反序列化为对应类型；
- `defineExpose` 的 12 个成员与 App.vue 顶栏按钮（loading/disabled/click）逐一对应，`ref`/`computed` 经实例代理自动解包，顶栏 `evalPanel?.currentRun` 等用法成立。

核心问题集中在**评测运行的异步竞态与生命周期管理**（H-1、M-1、M-2）：评测一跑就是分钟级，而组件对此期间的一切交互（切换数据集、标记基线、代理超时、页面刷新）都没有防护。

---

## 高严重度

### H-1 评测数据无请求序控制、完成回写无归属校验：慢运行期间的切换会让界面展示错误数据集的结果

- **位置**：`:402-414`（`runSelectedEvalDataset` 完成后**无条件** `evalComparison.value = { …, current: run }`，不校验 `run.datasetId` 是否仍是当前选中）；`:350-357` + `:359-363`（`loadEvalComparison` 与 `selectEvalDataset` 无序令牌/AbortController，后发先至的旧响应覆盖新选择）
- **描述**：两条确定性触发路径，同一根因：
  1. **完成回写竞态**：`triggerEvalRun` 同步等待整个评测跑完（常为分钟级，见 M-1）。期间用户点选了另一个数据集（`selectEvalDataset` 已加载新数据集的 comparison）；评测完成回调把旧数据集的 run 连同其 metrics/results 整体写入 `evalComparison`——此后指标卡、基线对比、结果表格、顶栏 runId 显示的全是**旧数据集**的数据，而侧栏高亮和标题是新数据集。对一款"基线对比决定是否发布"的工具，这是直接的错误数据展示。
  2. **切换竞态**：快速连点两个数据集，两个 `getEvalComparison` 并发，先发的慢响应后到，覆盖后发数据集的结果。
- **触发条件**：路径 1 = 触发评测 → 运行期间切换数据集 → 等待完成（分钟级窗口内极其常见）；路径 2 = 快速连点。
- **影响**：指标卡/表格/顶栏与侧栏选中项不一致，用户可能基于错误数据做基线判断；无任何提示可察觉。
- **修复建议**：① `runSelectedEvalDataset` 回写前校验 `run.datasetId === evalSelectedDatasetId.value`，不匹配则只提示"评测完成"并提供查看入口，不覆盖当前视图；② `loadEvalComparison` 引入单调递增的请求序号（或 AbortController），仅接受最新一次请求的响应；③ 运行期间对侧栏切换给出确认或禁用（与 M-2 配合）。

---

## 中严重度

### M-1 评测运行为分钟级同步等待：无进度、无轮询、无取消、无超时后恢复路径

- **位置**：`:388-421`（`triggerEvalRun` 一次性 await 到运行结束）；对照后端 `EvaluationController.java:38`（POST runs 同步返回）与 `:57`（**已存在** `GET /runs/{runId}` 可查询单次运行状态——前端完全未用）
- **描述**：整段运行期间前端只有 `evalRunning` 一个布尔（顶栏按钮转圈）。四个具体缺口：
  1. **代理/网关超时**：长运行超过中间层超时后 fetch 报错，`handleEvalError` 弹"评测运行失败"——但后端其实继续跑完了；用户看到失败，数据集 comparison 里却没有新 run（前端也不知道去查）；
  2. **页面刷新/切走再回**：`evalRunning` 随组件状态丢失（App 刷新后组件重建），进行中的运行无任何线索，`maybeLoadDatasets` 只在列表为空时才拉取；
  3. **无取消**：点错数据集的运行无法中止，只能等它跑完（烧模型费用）；
  4. **无进度**：`results` 按用例逐步产生，前端却只能等最终一个响应。
- **触发条件**：任何一次较长的评测运行（用例数 × LLM 延迟，分钟级是常态）。
- **影响**：最常见的工作流（跑一次评测）在时间稍长时体验崩坏：假失败、状态丢失、重复触发重复烧钱。
- **修复建议**：利用后端已有的 `GET /runs/{runId}`：`triggerEvalRun` 改为提交后轮询（或至少超时后转轮询），按 `run.status` 更新进度；失败路径上先尝试 `getEvalComparison` 再报错，避免假失败；如后端支持取消再补取消按钮。组件对外 props/emits 不需变更（App 经 `defineExpose` 调用，方法内部行为可平滑替换）。

### M-2 顶栏"设为基线/导出报告"在评测运行中不失效：可对旧 run 设基线并触发 comparison 覆盖（与 H-1 叠加）

- **位置**：`:423-435`（`markCurrentEvalRunBaseline` 无 `evalRunning` 守卫、无自身 loading 状态）；`:437-458`（`downloadEvalReport` 同样无运行中守卫）；父组件 `App.vue:80-86`（两个按钮只 `:disabled="!evalPanel?.currentRun"`，markBaseline 连 loading 都没有）
- **描述**：评测运行期间 `evalCurrentRun` 仍是**上一次**的 run（新 run 尚未回写）。此时点"设为基线"：① 语义上用户以为在给即将完成的运行设基线，实际操作的是旧 run；② 成功后 `loadEvalComparison(run.datasetId)` 发起覆盖请求，与运行完成回写（H-1 路径 1）构成三方竞态，最终显示哪个完全取决于响应到达顺序；③ markBaseline 无 loading，可连点发多次请求。导出报告类似——导出的是旧 run 的报告，按钮却无任何运行中提示。
- **触发条件**：评测运行中点击顶栏"设为基线"或"导出报告"。
- **影响**：基线被错误设定（写操作，影响后续所有对比），视图数据错乱。
- **修复建议**：两个方法开头加 `if (evalRunning.value) return`（或 ElMessage.warning）；组件 expose 一个 `baselineMarking` 状态供 App 绑定 loading；App 侧补 `:disabled="… || evalPanel?.running"`。

### M-3 `loadEvalComparison` 失败即清空整个对比视图：瞬时网络错误把已渲染的有效数据一并抹掉

- **位置**：`:350-357`（catch 中 `evalComparison.value = null`）
- **描述**：切换数据集时若 comparison 请求瞬时失败（网络抖动、后端重启），整个 `evalComparison` 被置 null——指标卡全变"-"、基线/当前 run 变 "none"、表格清空。此前正在查看的另一数据集数据也一并丢失（因为它在同一个 ref 里）。唯一的恢复手段是再点一次数据集按钮，界面无任何"重试" affordance，只有一条会自动消失的 ElMessage。
- **触发条件**：任意一次 comparison 请求失败。
- **影响**：可用性断崖；与 H-1 共同构成"comparison ref 承载多数据集状态但无一致性管理"的问题面。
- **修复建议**：失败时保留旧值并显示局部错误条 + 重试按钮；或在组件内按 datasetId 缓存 comparison、失败时回退缓存。若坚持置空以避免数据错配，至少把错误状态暴露为响应式状态供模板渲染持久错误 UI（而非 toast）。

---

## 低严重度

### L-1 外部设置 `selectedDatasetId` 时只同步镜像、不刷新 comparison

- **位置**：`:197-204`
- **描述**：watch 把 props 变更同步进本地镜像，但不触发 `loadEvalComparison`。当前调用方（组件自身的 `setSelectedDatasetId`）总是伴随手动加载，故无实际故障；但该契约是隐式的——将来父组件任何直接赋值（如从会话恢复、深链）都会出现"高亮选中 A、数据显示 B"。
- **修复建议**：watch 回调中对非镜像来源的变更调用 `void loadEvalComparison(value)`（与镜像同步合并防抖）。

### L-2 JSON 语法错误无定位信息

- **位置**：`:295`（`JSON.parse` 抛 SyntaxError）+ `utils/errors.ts:103`（英文报错被 `isReadableText` 过滤，最终只显示"评测集创建失败"）
- **描述**：用户粘贴的 JSON 少个逗号时，错误提示不包含行列位置（SyntaxError 的 message 是英文，被过滤机制整体丢弃）。11 行的输入框里靠肉眼找语法错误体验差。
- **修复建议**：`parseEvalDatasetJson` 内单独 catch SyntaxError，解析其 message 中的 position 并换算行列，或直接用中文提示"JSON 语法错误（第 X 行）"。

### L-3 `normalizeEvalCase` 不查重 caseId，重复创建同名数据集也无提示

- **位置**：`:279-292`（caseId 缺失时按序号兜底，但不检测用户 JSON 内重复 id）；`:365-386`（同名数据集可反复创建，列表出现多个同名项仅靠 shortId 区分）
- **描述**：重复 caseId 的行为完全依赖后端（EvalCaseCreate 无前端校验）；同名数据集堆积后 `shortId` 前 10 位可能都相同（时间戳前缀相近），难以辨认。
- **修复建议**：提交前 `Set` 查重并在 UI 标注重复项；创建前对现有 `evalDatasets` 做同名提醒（不阻塞，confirm 即可）。

### L-4 报告下载的 `revokeObjectURL` 同步执行

- **位置**：`:445-452`
- **描述**：`link.click()` 后立即 `URL.revokeObjectURL(url)`。主流浏览器对已开始的下载通常仍能完成，但该时序在部分浏览器/大文件场景下可能中断下载；惯例是延迟回收。
- **修复建议**：`setTimeout(() => URL.revokeObjectURL(url), 10_000)` 或在 `link` 上监听完成后回收。

### L-5 结果表状态列直出英文原始值，失败行无视觉强调，且无分页

- **位置**：`:82`（`prop="status"` 直出 `passed/failed/...`）、`:75-93`（el-table 全量渲染 results，无分页/虚拟化）
- **描述**：status 是后端枚举原文，未翻译也无颜色标记，用户需逐行扫读找 failed；用例数上百时 el-table 全量渲染开始卡顿。
- **修复建议**：状态列加 formatter + tag/颜色（failed 红）；数据量大时加分页或 `el-table-v2`。

### L-6 响应式断言对 grid 容器写 `flex-direction`（死规则）

- **位置**：`:755-760`（`@media (max-width: 900px)` 中 `.evaluation-page { flex-direction: column; }`，而该元素 `display: grid`（`:499`））
- **描述**：迁移遗留：`flex-direction` 对 grid 容器无效，纵向堆叠实际由 980px 断点的 `grid-template-columns: 1fr` 完成；`overflow-y: auto` 则是有效但语义混杂的残留。与 MessageList.vue 的双段样式问题同源。
- **修复建议**：删除死规则，注释指明纵向堆叠由 980px 断点承担。

---

## 修复优先级建议

1. **先做 H-1（数据正确性）**：回写归属校验 + 请求序号/中止——两处小改动，消除"指标卡显示别的数据集结果"这一对评测工具最致命的错。
2. **再做 M-1 + M-2（运行生命周期）**：基于已有 `GET /runs/{runId}` 补轮询与失败恢复，运行中禁用基线/导出/（可选）切换——决定"跑一次评测"这条主路径在真实时长下是否可用。
3. **M-3**：comparison 失败保留旧值 + 局部重试，避免瞬时错误清屏。
4. **低严重度项**：JSON 定位、查重、下载回收、状态列、死规则，均可随上述批次顺带完成。
