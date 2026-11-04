# 架构分析报告：api-recipes.md 的模块边界与依赖方向

- **分析日期**：2026-11-04
- **分析方式**：只读通读 + 静态依赖核查（未执行、未改动任何源码与文档；不运行任何 build/mvn；全部结论来自静态交叉审查，每条结论附文档行号与代码位置证据）
- **分析对象**：`docs/api-recipes.md`（122 行：公共头声明 :3-29 + Chat :31-43 + PDF 摄取 :45-69 + PDF RAG :71-79 + ReAct :81-101 + History/Audit :103-113 + Health :115-122）
- **核查范围**：文档「依赖面」逐条对码——8 个端点的存在性与 HTTP 方法逐一核对（ChatController.java:48、PdfController.java:46/:73/:99、IngestionController.java:40/:54/:63、ChatHistoryController.java:25、AuditController.java:21、ReactController.java:23/:32、AuthController.java:54）；凭证前提三层下钻（V7__seed_demo_admin_api_key.sql → V15__revoke_seeded_demo_api_keys.sql → BootstrapProperties.java:19 → docker-compose.yml / docker-compose.lite.yml:32 / .env.example 全量 grep）；`/auth/token` 租户头语义（AuthController.java:55-64）与 `RequestContextFilter.java:38-42`；actuator 权限（SecurityConfiguration.java:51/:53）；文档引用图双向核查（出链 :122 operations.md；入链 README.md、docs/index.md、docs/getting-started.md grep 证实）；平行文档内容 grep（getting-started/demo-script/deploy-云服务器手册/学习手册）
- **约束遵守**：未改源码与文档、仅新增本报告、无 git 操作、无删除、无 npm install/build、动笔前已完整读目标文件、未变更任何对外 API 签名（文档声称的端点行为仅记录不动，修复建议均为文档侧编辑）
- **关联报告**：`audit-api-recipes.md.md`（同文件缺陷审查：每条 curl 逐一对后端核对、种子 key 三层下钻、200+ok=0 被 curl 退出码掩盖等——本报告只从边界角度归位引用不重审）；`arch-AuthController.java.md`（/auth/token 签发五步、refresh cookie 预埋契约）；`arch-IngestionService.java.md`（幂等键全局 UNIQUE 模型——4.5 的代码侧依据）；`arch-client.ts.md`（前端消费同批端点的对照面）

## 1. 模块定位

本文档是**平台对外 HTTP 面的速查手册（recipes 类文档）**：以「可直接粘贴的 curl」为单位交付六个域的操作配方——鉴权换取（:26-29）、Chat（:31-43）、PDF 摄取与作业查询（:45-69）、PDF RAG（:71-79）、ReAct（含 SSE，:81-101）、历史/审计（:103-113）、健康与指标（:115-120）。

**覆盖面边界（成文事实）**：文档只覆盖演示主链，不是全 API 面——`/ai/sessions`、`/ai/evaluation`、`/ai/feedback`、`/cost/*`、`/memory`、`/ai/sessions/*/branches` 等前端核心消费域均无 recipes（对照 arch-client.ts 2.2 的 21 个消费端点清单，本文档与其交集仅 react/chat 两端点）。这本身是合理的 scope 选择，但文档**没有声明这一边界**（标题与开头无「覆盖范围」说明），读者会把「没有 recipes」误读为「没有端点」。记录项（6）。

**文体承诺**：每条 recipe 隐含三重契约——端点存在且方法匹配、前置条件可达成、响应可被 curl 语义正确解读。下文按此三重契约评估。

## 2. 依赖全景

### 2.1 出边（文档对代码/资产的声称清单）

| 依赖 | 位置 | 核实结果 |
| --- | --- | --- |
| `GET /ai/chat`（:33-37） | ChatController.java:48 = **@PostMapping**，produces text/html | ❌ 方法失配（4.2）；`modelProfile`/`chatId` 参数本身存在（ChatController.java:52/:60） |
| `POST /ingestion/upload/{chatId}` + `X-Idempotency-Key`（:48-53） | IngestionController.java:40 ✅ | 方法/路径正确；幂等键语义缺陷见 4.5 |
| `GET /ingestion/jobs/{jobId}`、`GET /ingestion/jobs?chatId&limit`（:58/:66） | IngestionController.java:54/:63 ✅ | 全对（正面） |
| `GET /ai/pdf/chat`（:74-77） | PdfController.java:99 = **@PostMapping**，text/html | ❌ 方法失配（4.2） |
| `POST /ai/react/chat`、`POST /ai/react/chat/stream`（:86/:96） | ReactController.java:23/:32 ✅ | 全对，与 client.ts 消费契约互证（正面） |
| `GET /ai/history/chat`（:106） | ChatHistoryController.java:25 `@GetMapping("/{type}")`，type=chat ✅ | 正确 |
| `GET /audit/logs`（:110） | AuditController.java:21 ✅ | 正确 |
| `POST /auth/token`（:26-29） | AuthController.java:54 ✅ | 方法对；租户头语义声称正确（4.4），失败形态缺陷见 4.4 |
| `GET /actuator/health`、`GET /actuator/prometheus`（:118-119） | SecurityConfiguration.java:51/:53 | health permitAll ✅ 裸 curl 可用；**prometheus 需 PERM_METRICS_READ/ROLE_ADMIN/ROLE_OPS**，:119 裸 curl 必 401/403（4.3） |
| 种子 API key（:13-17「seeded API key」、:23「seeded development value from .env.example」） | V7 种子已启用、**V15 已吊销**；.env.example 无平台 key（仅上游 OPENAI_API_KEY 与 APP_API_KEY_EXPIRE_DAYS） | ❌ 前提断裂（4.1，核心） |
| `demo-data/heat-safety-policy.pdf`（:52） | demo-data/heat-safety-policy.pdf 存在 ✅ | 资产引用有效 |
| 「local Docker Compose stack」（:3） | 主 docker-compose.yml **未注入** APP_BOOTSTRAP_API_KEY（仅 docker-compose.lite.yml:32） | 前提与注入面错位（4.1） |
| `[Operations Manual](operations.md)`（:122） | docs/operations.md 存在 ✅ | 唯一出链 |

### 2.2 入边（反向引用）

- README.md、docs/index.md、docs/getting-started.md 三处链接指向本文档（grep 证实）——它是新人路径上的**第一份可执行文档**，其前提断裂（4.1）会被入链放大为「第一天就跑不通」。
- 无测试/脚本引用本文档（eval 脚本各自内联端点，见 5.2）。

### 2.3 隐式依赖：租户语义声明（:19-21）

文档主动声明了租户头语义：「API key or JWT tenant is authoritative and the tenant header cannot switch authorization scope」——与 `RequestContextFilter.java:38-42`（认证身份租户权威，header 仅未鉴权时生效）**逐字吻合**，且「must match the API key during token exchange」与 AuthController.java:63 的 mismatch 检查吻合。**这段是全文档与代码对齐度最高的一节**（正面），但声明了正确的语义却配了错误的默认值（TENANT_ID=default，4.4）。

## 3. 循环依赖评估

**文档引用图无环**：本文档唯一出链是 operations.md（:122），后者未回链本文档（grep 证实），单向引用构成「速查 → 运维」的正向阅读流。

- **文档↔代码是单向声称关系，非环**：文档引用端点，代码不引用文档——这类「依赖」的正确性只能靠对码维持，本次对码发现的漂移（4.1-4.5）全部是单向失检的产物。无 CI 机制把本文档的 curl 与控制器签名对表（对照 eval/ 脚本至少可运行，文档连可执行性验证都没有），属**无回归锚点的单向契约**，与 arch 系列反复记录的「口头契约无类型强制」同型。记录项：可考虑把 recipes 改造为可执行 smoke 脚本（eval/ 已有同类先例）。
- 无文档间循环引用；与 getting-started/demo-script 的内容重复是无引用的平行（5.1），不构成环但构成漂移面。

## 4. 越层评估

（文档类对象的「越层」=越过真实前提/信任边界的教学；即 recipe 声称的路径在真实栈上走不通或教错边界。）

### 4.1 凭证获取链三段断裂：按文档操作第一步即无凭证可用（核心）

文档的 key 前提链条逐环核实，**三环全断**：

1. **「seeded API key」（:13-17）已不存在**：V7__seed_demo_admin_api_key.sql 曾种入 `demo-admin-key-2026`（tenant `public`），**V15__revoke_seeded_demo_api_keys.sql 已将其与 V1 的 dev-default key 一并吊销**（enabled=0 + revoked_reason 自证「publicly committed seed credential」）。任何跑过 V15 的栈上不存在可用的「seeded」key；
2. **「the seeded development value from .env.example」（:23）查无此值**：.env.example 全文只有上游 LLM 凭据（OPENAI_API_KEY/OPENAI_BASE_URL）与应用配置，**没有任何平台 X-API-Key**——且 :15 邻近出现的 OPENAI_API_KEY 正是 audit 报告记录的「上游凭据不能当平台 key」陷阱的诱饵位；
3. **现实的 key 注入通道与文档前提错位**：`APP_BOOTSTRAP_API_KEY` 只在 docker-compose.lite.yml:32 注入，主 docker-compose.yml、.env.example、Makefile 均无——而文档 :3 的前提是「local Docker Compose stack」（主 compose）。

三段合流：一个按文档操作的新人（README → getting-started → api-recipes 的入链路径）在任何一种本地栈上都拿不到文档假设的 key。修复位：重写 Common Headers 节（:5-29）为「先 lite compose bootstrap → 导出 APP_BOOTSTRAP_API_KEY → 再 exchange」的真实链路，或在主 compose 补注入 + .env.example 补键（涉及部署面，属直接相关文件）。

### 4.2 两处 GET curl 打在 POST-only 端点上：必 405

- `/ai/chat`（:33-37，裸 GET）：ChatController.java:48 是 `@PostMapping` 且 produces **text/html**——除 405 外，即便方法对了响应也是 HTML 片段而非 JSON，recipe 的「可见文本回复」预期需要 -X POST + 响应形态说明；
- `/ai/pdf/chat`（:74-77，裸 GET）：PdfController.java:99 同款 `@PostMapping` text/html。

这两条是文档的**门面 recipe**（Chat 域的第一条与 RAG 域唯一一条），方法失配即文档核心承诺失效。修复位：加 `-X POST`（无需 body，两端点参数均走 query），并注明响应为 HTML 文本。文档侧编辑，不触代码。

### 4.3 `/actuator/prometheus` 裸 curl 缺权限前提

:118-119 两条并排展示，但权限面完全不同：health/info/error permitAll（SecurityConfiguration.java:51），prometheus 需 `PERM_METRICS_READ`/`ROLE_ADMIN`/`ROLE_OPS`（:53）——:119 的裸 curl 必被拒。修复位：prometheus recipe 补鉴权头 + 一句「需指标读权限」，并可与 operations.md 的告警章对接（那里才是 prometheus 的真实上下文）。

### 4.4 `TENANT_ID=default` 与栈的实际默认租户相抵，失败被 200 掩盖

:10 以 `TENANT_ID=default` 作公共头。而代码侧的默认租户是 **`public`**（BootstrapProperties.java:19；V7 种子 key 的 tenant_id 也是 `public`）——按 lite compose bootstrap 的默认路径（不显式设租户）取得的 key 属 `public`，带 `X-Tenant-Id: default` 调 /auth/token 会命中 AuthController.java:63 的 mismatch 检查。**失败形态是本报告最关键的一处跨层语义**：token 端点返回 **HTTP 200 + `ok=0` + msg「tenant mismatch for api key」**（AuthController.java:64）——curl 退出码 0，脚本与新人都会把响应当成功继续走，直到下游 401 才暴露且原因已不可见（audit-api-recipes 已记此模式，本报告归位到租户默认值这一具体诱因）。修复位：:10 改 `TENANT_ID=public` 或明确「与 bootstrap 租户一致」；同时在 4.1 的重写里补「/auth/token 失败返回 200+ok=0，需检查 .ok 字段」的操作须知。

### 4.5 `X-Idempotency-Key: demo-rag-001` 静态示例无复用语义

:51 以固定值作示例。代码侧幂等键是**全局 UNIQUE**（audit-IngestionService：DDL 全局唯一 + 服务层按租户域查询的错配），同一 key 第二次上传（新人重跑 recipe 的最常见动作）会撞唯一键 → DuplicateKeyException 路径。修复位：示例值改含随机/时间戳占位（如 `demo-rag-$(date +%s)`）并加一句「同 key 重传将被幂等拒绝」——正好把幂等键的**正确用法**也教了，文档价值反增。

### 4.6 信任边界的教学是本文件的存量正面项（无修复）

:19-21 的租户权威声明（2.3）是罕见的「文档主动教信任边界」段落，与代码逐字对齐——作为 4.1 重写时的锚点保留，不要在改写中丢失。

## 5. 平行实现（文档版）

### 5.1 断裂的凭证链被平行复制到至少 5 份文档（本报告核心平行发现）

`<local-demo-api-key>` 占位符 + 相同头模式出现在：本文件 :9、getting-started.md:49/:59、demo-script.md:24，另 deploy-云服务器手册.md 与 docs/学习手册/03-本地跑起来-十步.md 也含 auth/token 或 X-API-Key 配方（grep 证实）。**没有任何一份是引用本文件的单点**——每份各自内联同一个已断裂的前提（4.1）。后果：修复本文件的 key 链而不同步其余四份，新人路径（README → getting-started）依然首步即断，因为**入链流量主要落在 getting-started 而非本文件**（2.2）。修复位：五份文档的凭证获取节收敛为单点（getting-started 作为唯一教程，本文件降为引用），或最低限同步改写——均属文档侧改动。

### 5.2 与 eval 脚本的平行：同一批端点三套独立客户端

本文件（curl）、scripts/eval_demo.py、e2e_chat_flow.py 各自内联同一批端点调用，无共享夹具——端点漂移时要改三处且无联动信号（audit 系列已在脚本侧记录各自的漂移实例：eval_demo 的兜底 key 未随 V15 跟进等）。与 5.1 合并看：**同一 API 面存在「文档×5 + 脚本×2 + 前端 client.ts×1」八套平行消费描述**，其中唯有 client.ts 受编译器约束。记录项：以 client.ts/契约测试为权威源的方向已在前端成立，文档与脚本侧的收敛是长期项。

### 5.3 排除性结论（正面）

- 摄取域三条 recipe（:48-69）方法/路径/参数全对，且 jobs 查询补全了「提交→轮询」闭环——是全文档最扎实的一节；
- ReAct 两条（:86/:96）与前端 client.ts:176/:281 的调用形态完全一致（JSON body、SSE `curl -N` POST），`-N` 标志对 SSE 的用法正确；
- 唯一出链 operations.md 有效；demo-data 资产引用有效。**不存在编造端点**（对照 arch-HybridRetrieval 记录的「文档虚构 /ai/rag/search」——本文档所有端点均真实存在，问题全部集中在方法与前提层）。

## 6. 修复优先级建议（均为文档侧编辑，不改任何对外 API 签名；改动仅限本文件+直接相关文档）

1. **P1** 凭证获取链重写（4.1）：以「lite compose bootstrap → APP_BOOTSTRAP_API_KEY → /auth/token exchange」为唯一链路，明确 V15 后无 seeded key、.env.example 的 OPENAI_API_KEY 不是平台 key；同步标注 /auth/token 200+ok=0 失败形态（4.4 后半）。
2. **P1** 两处 Chat recipe 改 `-X POST` 并注明 text/html 响应形态（4.2）。
3. **P2** `TENANT_ID=default` 与栈默认租户对表（4.4，改 `public` 或注明一致性要求）；prometheus recipe 补权限前提（4.3）；幂等键示例改动态值 + 复用语义务（4.5）。
4. **P2** 五份平行文档的凭证节收敛/同步（5.1）——注意入链流量在 getting-started，不同步等于没修。
5. **记录项**：覆盖面边界声明（1，列明未覆盖域并指向 client.ts/控制器清单）；recipes 可执行化（3，smoke 脚本先例在 eval/）；保留 :19-21 信任边界段落（4.6）。
