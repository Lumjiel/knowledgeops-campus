# 架构分析报告：EvaluationPanel.vue 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码；前端环境无 node_modules，不运行任何 install/build/lint，全部结论来自静态交叉审查；每条结论附源码位置证据）
- **分析对象**：`frontend/src/components/EvaluationPanel.vue`（767 行 = template :1-96 + script :98-474 + style :476-767）
- **核查范围**：唯一调用方 `App.vue` 的绑定面逐 prop/逐 event/逐 expose 成员核对（:49-52 头部摘要、:67-88 顶栏动作、:132-140 组件接线、:230/:232-243/:275/:403/:504）；`api/client.ts` 六个评测端点封装（:541-660）；`types/react.ts` 评测类型族（:51/:151-228）；`composables/useGuestRestriction.ts` 全文；`utils/session.ts`（shortId :156）、`utils/errors.ts`（toUserMessage :91）；后端契约生产端 `EvaluationController.java`（:23-67 全部端点）、`EvaluationService.resolveChatId`（:370-379）；种子/夹具数据源排除性核查（`evaluation/demo-paths-dataset.json`、`evaluation/dataset.large.json`、`eval/`、`scripts/eval_demo.py`）；前端 api/client 直连面与格式化函数全前端 grep
- **约束遵守**：未改源码、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（props/emits/defineExpose 三通道均只记录不动）
- **关联报告**：`audit-EvaluationPanel.vue.md`（同文件缺陷审查：慢操作窗口错位、GET /runs/{runId} 未用端点等，本报告仅从边界角度引用不重复展开）；`arch-MessageList.vue.md`（同层前端 arch 先例：其「纯展示层」结论是本组件「智能组件」对照的基准；两代样式级联覆盖发现同型）；`arch-EvaluationService.java.md`（后端评测链路：resolveChatId 跨 run 污染、执行模型双轨）

## 1. 模块定位

本组件是**评测工作台的自持智能组件**：数据获取、请求编排、错误出口、状态机全部内联在组件内（对照 MessageList——纯展示层，请求编排全部在父侧 composable）。职责四块：

1. **数据集管理**（template :3-51 + script :325-386）：列表展示、选中同步（v-model）、JSON 表单创建（含三形态解析器 :294-323）；
2. **评测结果呈现**（:53-94）：指标卡（:219-259 含 delta 好坏判定）、基线/当前对比、结果表；
3. **运行编排**（:388-435）：触发运行、标记基线——分钟级同步操作（后端 triggerRun 内联逐 case 跑 LLM，见 arch-EvaluationService）；
4. **报告导出**（:437-458）：Blob + 临时 `<a>` 下载。

**三通道契约**：4 props（`authContext` getter、`modelProfile`、`selectedDatasetId`、`guestMode`，:161-167）+ 2 emits（`update:selectedDatasetId`、`persist`，:169-172）+ `defineExpose` **12 成员**（:460-473，5 方法 + 7 个 reactive ref 直借）。

**调用方唯一**：`App.vue`。注意其常驻方式与 MessageList 相反——本组件 `v-show="activeView === 'evaluation'"`（App.vue:133）常驻不卸载（App.vue:132 注释自证「避免切换视图丢失已加载的评测数据」），因此组件内 `evalDatasets`/`evalComparison` 状态跨视图存活；MessageList 则 `v-if` 每切必重建。两兄弟组件一 v-if 一 v-show，是 App.vue 有意的状态所有权分配：**谁持有易失状态，谁常驻**。

## 2. 依赖全景

### 2.1 出边

| 依赖 | 层级 | 使用点 | 方向评价 |
| --- | --- | --- | --- |
| `types/react.ts` | 同 src 类型契约 | `AuthContext`/`EvalCaseCreate`/`EvalComparison`/`EvalDataset`/`EvalMetricSummary`/`EvalRun`（:117-124） | 叶子类型文件，与后端 VO 一一对应（react.ts:151-228） |
| `api/client.ts` | 前端数据层 | 6 个函数（:106-113）；调用点 :328/:352/:368/:394/:429/:444 | **组件直连数据层**，见 4.1/4.6 |
| `utils/session.ts` | 同 src 工具 | `shortId`（:114 → template :20） | 单点消费，正面（对照 Sidebar 私有副本，见 5.2） |
| `utils/errors.ts` | 同 src 工具 | `toUserMessage`（:115 → :180） | 唯一错误文案出口的一部分 |
| `composables/useGuestRestriction` | 同 src 单例 composable | `isGuestForbidden`/`reportGuestRestriction`（:116 → :176-177） | **全前端唯一组件侧消费点**（.vue 文件 grep 证实），单例状态 + App 注入登录动作的约定（见 5.3） |
| `element-plus` | npm | `ElMessage`（:104 → :180/:380/:415/:431/:452 五处）、`el-collapse`/`el-input`/`el-button`/`el-table`（template） | 叶子直调全局 toast，与 ChatInput/MessageList 同约定（5.4） |
| `vue` 运行时 | 框架 | :105 | 标准三件套 |
| 浏览器 API | 平台 | `Blob`/`URL.createObjectURL`/`revokeObjectURL`/`link.click`（:445-451） | 下载通道有 revoke 清理，无泄漏 |

**关键依赖事实——组件对 api/ 的直连在前端是少数派**：7 个组件中仅 2 个 import `api/client.ts`（本组件与 ChatInput.vue:65），其余请求编排全部住在 composables（useChatEngine/useAuth/useSessionStore，grep 证实）。即前端存在两种边界风格并存：**composable 编排层（主流）vs 组件内联编排（本组件）**。方向上仍严格单向向下（App → 本组件 → api → 后端），不构成结构问题，但记入 4.1 的长期收敛方向。

### 2.2 入边

- main 源码 1 个调用方（App.vue），消费面 = 4 props + 2 events + v-model + 12 个 expose 成员。
- expose 成员逐项核对消费情况（App.vue 全文 grep `evalPanel`）：
  - **已消费 11 项**：`loadDatasets`/`runSelected`/`exportReport`/`markBaseline`（顶栏按钮 :67-88 调用）、`maybeLoadDatasets`（App.vue:403/:504 两处惰性加载）、`loading`/`running`/`exporting`/`selectedDatasetId`（顶栏 :disabled/:loading 绑定）、`currentRun`（App.vue:51-52 头部摘要 + :80 disabled）、`selectedDataset`（App.vue:49 标题）；
  - **零消费 1 项**：`datasets`（:466，暴露了整个 `evalDatasets` 数组 ref，App.vue 全文无 `evalPanel?.datasets`）——**死 expose 面**，是 4.1 状态外借模式成本的第一证据。
- 无测试引用（前端无组件测试设施）。

### 2.3 样式横切依赖（隐式契约）

与 MessageList 同款：设计令牌 `--ui-*`（:480-486/:508-547/:696-724 等大量使用）由 App.vue `.app-shell` 运行时继承提供，编译期不可见；覆盖层还消费 `--el-collapse-*`/`--el-table-*`（:674-676/:722-725），依赖 element-plus 变量名稳定。组件对样式基座有**不可自我验证的假设**（同 arch-MessageList 4.7）。

## 3. 循环依赖评估

**组件级与文件级均无环。**

- import 图：`App.vue → EvaluationPanel.vue → {api/client, types, utils, composables, npm}`，全部单向；本组件不回指 App、不 import 任何兄弟组件。下游文件中 `api/client.ts` 零回指组件、`useGuestRestriction` 只 import `utils/errors`（叶子单例），`utils/*` 零依赖——**出边全叶**。
- 数据流闭环（子 emit `update:selectedDatasetId` → App `v-model` 回写 prop → watch :197-204 镜像回本地 ref）是 Vue 标准双向环，非依赖环。
- **props 本地镜像的双真相同步**（:193-209）：`evalSelectedDatasetId` 本地 ref + watch 同步 props + `setSelectedDatasetId` 双写（本地 ref + emit）。注释 :193-194 自证了理由（props 回传渲染时序延迟，需同 tick 可读）且声明与拆分前行为一致——**同步规则完整**（watch 处理外部回写、双写函数处理内部变更，两个写入口都收敛），当前无漂移缺口。但它是「一个值两处真相」结构，风险预埋：未来任何人绕过 `setSelectedDatasetId` 直接写 `evalSelectedDatasetId.value`（如调试代码），镜像即失步且无报警。记录项，无修复动作。

## 4. 越层评估

### 4.1 `defineExpose` 十二员：命令通道 + 状态外借的双通道，组件 UI 控制权分裂在两个文件（核心越层项）

本组件的 expose 面是 MessageList（2 个方法，arch-MessageList 4.6 评为「最小实例正面样板」）的 6 倍，且性质不同：**5 个方法 + 7 个 reactive ref 直借**。App.vue 顶栏（:67-88 的刷新/运行/导出/基线四个按钮）和头部摘要（:49-52 的数据集名/runId/status）直接读写子组件内部状态——结果是**评测工作台的 UI 被劈成两半**：数据集列表、创建表单、结果表在子组件 template；动作按钮、运行状态展示在 App template，两者靠 expose 隐式管道耦合，肉眼无法从任一文件看出完整 UI。

具体成本已显形：

1. `datasets` 死面（2.2）——外借 7 个 ref 中 1 个从未被读；
2. App.vue 顶栏读 `evalPanel?.loading` 这类跨组件 reactive 绑定，让子组件内部状态机（evalLoading/evalRunning/evalReportExporting）成为跨文件公共契约，改任何一个内部 ref 名都会碎 App 顶栏——**expose 没有类型约束**（InstanceType 推断是唯一保护，App.vue:275）；
3. 这不是 bug 而是结构债：根因是顶栏按钮属于页面框架、状态属于面板，Vue 缺少第三条通道，才借道 expose。

评价与方向：**修复首选是把评测状态编排下沉为 `useEvaluation()` composable**（状态住 store，App 顶栏与面板共享同一实例，expose 收缩到 maybeLoadDatasets 一个触发点即可）——与 useChatEngine 模式对齐，不改现有 props/emits 签名；expose 成员的增删属于对外签名变更，列为未来收敛项、需与 App.vue 同批动作，本次不动。

### 4.2 `authContext` getter prop 与 `guestMode` prop：约定一致的依赖注入（记录项，无缺口）

凭据以闭包注入（`authContext: () => AuthContext`，:162），组件在每次请求现场调用（:328/:352/:368/:394/:429/:444 六处）——「凭据如何来」的知识留在 App/useAuth，组件不缓存不感知 token 生命周期。MemoryHintBar 采用同款签名（MemoryHintBar.vue:29/:42），**前端约定一致，属正面 DI 样板**。`guestMode` prop（:166）+ `isGuestForbidden(error, props.guestMode)`（:176）让组件对全局会话模式保持被动只读，方向正确。均记录不修。

### 4.3 评测种子数据内嵌 UI 组件 + `chatIdPrefix` 跨栈死参数（核心越层项）

`DEFAULT_EVAL_DATASET`（:134-159）是三条完整评测用例（含 expectedKeywords/forbiddenKeywords/expectedCitations 领域断言），长在展示组件里。两组跨栈后果：

1. **种子数据多源平行**：仓库内评测种子至少三处各自成源——本组件 :134-159、`evaluation/dataset.large.json`（240 例，含 `chatId`/`expected_keywords` 字段）、`scripts/eval_demo.py:87`（前缀 `eval-demo`）。三者无引用关系，语义漂移无信号（对照后端 arch 系列的「抽取件已存在但平行实现不接」同型）。
2. **chatIdPrefix 死参数**：请求里带 `chatIdPrefix: 'eval-studio'`（:398），但种子三例全部自带 `chatId`（:138 `eval-rag-a`、:147/:154 `eval-rag-b`），而后端 `resolveChatId`（EvaluationService.java:371-373）**case 自带 chatId 优先于请求前缀**——对该组件创建/内置的用例，前缀恒不生效。且 chatId 经 ConversationIdHelper → MysqlChatMemory 持久化（arch-EvaluationService 已证），同 chatId 跨 run 污染评测分。修复位：种子数据外迁 `evaluation/` 或 `demo-data/` 单点，case 级 chatId 与请求级 chatIdPrefix 二选一——均为直接相关文件内动作，不改签名。

### 4.4 `parseEvalDatasetJson`：UI 组件内嵌数据集夹具 schema 解析知识，且只对齐了一半（核心越层项）

:294-323 的解析器容忍三种容器形态：**裸数组**、**`{cases: [...]}`**、**`{paths: {路径名: {cases: [...]}}}`**。逐形态对库内核实：

| 形态 | 库内唯一同形态文件 | 实测 case schema | 解析结果 |
| --- | --- | --- | --- |
| 裸数组 | `evaluation/dataset.large.json`（240 例） | `id`/`question`/`category`/`chatId`/`expected_keywords`/`forbidden_keywords` | ✅ 可用（snake_case 容忍 :280-282 与 `raw.id` :284 恰好对上） |
| `{cases}` | 通用创建请求（EvalDatasetCreate） | camelCase | ✅ 可用 |
| `{paths}` | `evaluation/demo-paths-dataset.json`（`description`/`version`/`paths`/`metrics`，`paths.deep_research.cases`） | `expect`/`id`/`topic`，**无 `question` 字段** | ❌ **恒失败**：case 被 :318 `filter((item) => item.question)` 全部丢弃 → 抛「评测集 JSON 没有可用 case」（:320） |

即：`paths` 分支认识 demo-paths 的**容器 schema**、不认它的 **case schema**——对库里唯一同形态文件粘贴即败，且该失败是静默的语义错位（用户看到的是「没有可用 case」，真实原因是字段名不匹配）。这证明解析知识是从某代夹具格式**残留在 UI 层**的：schema 知识本该住数据层（utils/ 或后端导入端点），住组件里既无法复用也无测试锚点，漂移无人知晓（本次就是靠逐文件对表才发现的）。修复位：解析器下沉 `utils/evalImport.ts` + 对 demo-paths schema 做决议（支持则补 `topic/expect → question/keywords` 映射，不支持则删 paths 分支）——不改签名。

### 4.5 run→comparison 收敛双路径：同文件两条保真度不同的装配线（核心越层项，与 audit 发现闭环）

运行完成后刷新对比数据存在两条同文件平行路径：

- **`runSelectedEvalDataset`（:402-414）本地手装**：用返回的 run 拼一个 `EvalComparison`，`dataset` 兜底对象（:404-411）把 `name` 落成 `run.datasetId`、`createdAt` 落成 `run.createdAt`——**展示语义失真的伪造行**；
- **`markCurrentEvalRunBaseline`（:430）重取**：`await loadEvalComparison(run.datasetId)` 走后端权威。

同组件内一操作重取、另一操作手装，保真度分叉；手装分支正是 audit-EvaluationPanel 记录的「完成回写无 datasetId 归属校验」缺陷的载体（await 分钟级窗口内切换数据集，手装结果归属错位）。后端 `GET /comparison` 端点现成可用且本文件 :430 已在用——**收敛为统一 `loadEvalComparison(run.datasetId)`** 即同时消除伪造分支与错位窗口的主体（网络多一次往返，对该分钟级操作可忽略），不改签名。

### 4.6 组件直连 api 层（2.1 的架构面，记录项）

前端主流是「composable 编排」（useChatEngine 管聊天全链路），本组件把 6 个 API 调用 + 错误出口 + 加载状态机内联。方向不越层（api 层本就是组件可依赖的数据层），但**评测域没有自己的 composable**导致 4.1 的状态外借成为必然——两节同根。与 4.1 的 useEvaluation 方案一并收敛，本次不动。

## 5. 平行实现

### 5.1 两代样式并存靠级联顺序覆盖（同 arch-MessageList 5.1 同型，「原样迁移」的未了成本）

style 块第一代（:476-671，自 App.vue 迁入）+ 第二代覆盖层（:673-726，注释 :673 自证「原 App.vue 末尾覆盖段，随模板迁入」），后者靠源码顺序获胜。重复选择器至少 7 组：

| 选择器 | 第一代 | 覆盖层 |
| --- | --- | --- |
| `.eval-side-panel` | :505（卡片底 + 全边框） | :684（`background: transparent`，边改 right-only） |
| `.eval-dataset-list button` | :540（border/padding/背景） | :689（border: none、背景透明；**padding :542 与 radius :541 保留第一代**） |
| `.eval-metric-card` | :598（border-radius 10px + color-mix 底） | :700（radius 12px + `--ui-surface`） |
| `.eval-metric-card strong` | :613（margin/font-size） | :707（追加 font-variant-numeric，不冲突） |
| `.eval-run-summary` | :638 | :711（border/背景全改） |
| `.eval-run-summary.current` | :645（rgba 边） | :718（`--ui-accent-line`） |
| `.eval-result-table` | :667（radius/overflow） | :722（el-table 变量覆盖） |

被覆盖属性是死声明——改第一代不生效。另有一处**布局体系漂移**：`.evaluation-page` 基础样式是 `display: grid`（:499），而 `@media (max-width: 900px)` 段（:755-766，注释自称「原 App.vue 移动端抽屉段」）写的是 `flex-direction: column`（:758）——**grid 容器上 flex-direction 是永不生效的死声明**，说明该媒体查询从 flex 时代迁移后未随 :496-503 的 grid 重写更新（980px 断点 :734-742 的 grid 版本才是生效的那条）。核验后删死属性/死声明 + 给覆盖层注释锚定胜出关系，均在直接相关文件内。

### 5.2 `shortId`：本组件是单点消费的正面侧，旁边有逐字副本（漂移现场在邻屋）

本组件 import `utils/session.ts` 的 `shortId`（:114），而 **Sidebar.vue:167-169 自养逐字相同的私有副本**（`id.slice(0, 10)`，与 utils/session.ts:156-158 逐字等价）——同一函数三处存在、两种引入方式。EvaluationPanel 无需动作，Sidebar 收敛到 utils 即可（属其自身文件的改动，本报告仅记录现场）。

### 5.3 `handleEvalError` 统一错误出口：单一收敛 + 裸字符串契约（正面包边一个记录项）

六个动作全部经 :175-181 单一出口——游客 403 走引导卡片、其余走 `toUserMessage`，分支判定收敛、无散落 try/catch 文案。同时本组件是 `isGuestForbidden`/`reportGuestRestriction` 的**全前端唯一组件侧消费者**（.vue grep 证实），与 App.vue（dismiss/bind 注入侧）恰成单例 composable 的两半。唯一记录项：`reportGuestRestriction('检索评测')`（:177）的 feature 字符串必须精确命中 `useGuestRestriction.FEATURE_DETAILS` 的 key（『检索评测』）才有专属文案——**跨文件裸字符串契约**，拼写漂移静默降级到通用文案（useGuestRestriction.ts 的 `?? GENERIC_DETAIL` 兜底掩盖）。收敛方向：FEATURE_DETAILS 的 key 导出为常量（改 useGuestRestriction + 本组件两个直接相关文件），不改签名。

### 5.4 ElMessage 直调与格式化函数：排除性结论（正面）

- `ElMessage` 直调（5 处）与 ChatInput（3 处）/MessageList（5 处）同约定，前端无统一 toast 封装也无平行封装——**约定即单点**，记录非缺陷。
- `formatPercent`/`formatMetricValue`/`metricCard` delta 判定（:219-277）全前端唯一站点（含 `(value * 100)` 百分比换算 grep 排除）；`formatMetricValue` 对 `avgLatencyMs` 走 ms、其余走 percent 的分流与 `EvalMetricSummary` 字段一一对应。**无其他站点**。
- 无任何测试文件引用本组件（grep 排除），4.4 解析器、:232-259 指标卡判定均无回归锚点——与 MessageList 同状，属前端整体约束而非本组件特有。

## 6. 修复优先级建议（均不改对外 API 签名；改动仅限本组件+直接相关文件）

1. **P1** `runSelectedEvalDataset` 收敛为 `loadEvalComparison(run.datasetId)` 复用（4.5）：删除 :402-414 手装分支与 dataset 伪造兜底，与 :430 的既有模式对齐；顺带收窄 audit 记录的慢操作错位窗口。
2. **P1** 种子数据外迁 + chatId 策略二选一（4.3）：`DEFAULT_EVAL_DATASET` 移至 `evaluation/` 或 `demo-data/` 单点（组件内保留 JSON 默认文本或经 import 引入）；删除 case 级 `chatId` 或请求级 `chatIdPrefix` 之一，消掉 :398 死参数与跨 run 会话污染面。
3. **P2** `parseEvalDatasetJson` 下沉 `utils/evalImport.ts`（4.4），并对 `{paths}` 形态做决议：补 demo-paths 的 `topic/expect` case 映射，或删除该分支；snake_case 容忍范围与 `evaluation/` 夹具 schema 对表成文。
4. **P2** 两代样式清理（5.1）：核验后删第一代被覆盖死属性、删 :758 `flex-direction` 死声明（或改回 grid 语义），覆盖层注释锚定。
5. **P2 记录项**：expose 收缩与状态上提（4.1/4.6）——删 `datasets` 死面、抽 `useEvaluation()` composable 均触及对外签名与 App.vue 接线，**列为未来同批收敛项，本次不动**；执行时以 App.vue:49-88 顶栏消费面为准逐成员迁移。
6. **记录项**：`'检索评测'` 字符串 key 常量化（5.3）；props 本地镜像禁止绕过 `setSelectedDatasetId` 直写（3，靠约定）；Sidebar 的 shortId 副本归位 utils（5.2，属 Sidebar 文件）。
