# 审查报告：docs/api-recipes.md

- **日期**：2026-11-04
- **审查方式**：只读通读 + 对后端源码逐一核对每条 curl 的方法/路径/参数/鉴权契约（文档类审查的核心是把"配方"与真实实现比对，而非检查文风）
- **被审对象**：`docs/api-recipes.md`（123 行）：本地栈 curl 配方（鉴权、Chat、Ingestion、RAG、ReAct、历史/审计、可观测）
- **约束遵守**：未改任何源码；仅新增本报告文件；无 git commit/push；无删除文件；无 npm install / mvn build
- **关联报告**：`reports/audit-AuthController.java.md`、`reports/audit-ModelRouter.java.md`、`reports/audit-TenantCostService.java.md`、`reports/audit-client.ts.md`（前端 client.ts 对同一批端点的消费契约可交叉印证）

## 协作者清单（均已核实）

| 协作者 | 用途 | 关键证据 |
| --- | --- | --- |
| `ChatController.java` | `/ai/chat` 方法与参数 | ChatController.java:50 `@PostMapping("/chat", produces="text/html;charset=utf-8")` |
| `PdfController.java` | `/ai/pdf/chat` 方法与参数 | PdfController.java:88 `@PostMapping(value="/chat")` |
| `IngestionController.java` | `/ingestion/upload/{chatId}`、`/jobs/{jobId}`、`/jobs?chatId=&limit=` | IngestionController.java:44-76 |
| `AuthController.java` | `/auth/token` 契约与租户匹配 | AuthController.java:55-64（tenant mismatch → ok=0） |
| `SecurityConfiguration.java` | 各端点权限矩阵 | :48（security 关闭时全放行）、:51-55、:58-68 |
| `BootstrapApiKeyInitializer.java` / `BootstrapProperties.java` | "种子 API Key" 机制 | BootstrapProperties.java（默认 tenant `public`、role `ADMIN`） |
| `application.yml` / `.env.example` / `docker-compose*.yml` | actuator 暴露、profile 取值、端口、种子注入 | application.yml:66-87；docker-compose.lite.yml:32 |
| `RagAnswerService.java` | 回答是否含引用行 | :150 `answerWithFooter = answer + formatCitationFooter(citations)` |
| `ModelRouter.java` / application.yml:137-151 | `modelProfile` 合法取值 | economy/balanced/quality 均存在 |

---

## 概述：先说做对的部分

1. **端口与基地址正确**：主 compose 与 lite compose 均映射 `8080:8080`（docker-compose.yml:7-8、docker-compose.lite.yml:53），`BASE_URL=http://localhost:8080` 成立。
2. **租户头语义的免责声明准确**：文档"API key or JWT tenant is authoritative and the tenant header cannot switch authorization scope … must match the API key during token exchange"与 AuthController.java:63-64 一致——租户头不匹配时换发返回 `ok=0 "tenant mismatch for api key"`。
3. **Ingestion 三条配方完全正确**：`POST /ingestion/upload/{chatId}`（multipart `file` + 可选 `X-Idempotency-Key`，IngestionController.java:44-53）、`GET /jobs/{jobId}`、`GET /jobs?chatId=&limit=`（limit 默认 20 且有 1-100 夹取）均与实现吻合。
4. **ReAct 两条配方正确**：`POST /ai/react/chat` JSON body（prompt/chatId/modelProfile）与 `POST /ai/react/chat/stream` 与 client.ts:171-334 及 SecurityConfiguration.java:59 的契约一致；stream 示例省略 modelProfile 合法（可选）。
5. **`/ai/history/chat` 正确**：ChatHistoryController 的 `GET /ai/history/{type}`（type=chat）存在，返回 `PagedResult<String>`。
6. **`modelProfile` 取值正确**：economy/balanced/quality 三个 profile 均在 application.yml:137-151 定义。
7. **RAG 引用描述准确**：有命中来源时 `RagAnswerService` 确实把 citation 行以脚注拼进 answer 文本（RagAnswerService.java:150、:228 `source=…, chunk=…`），"response includes answer text and citation lines" 成立。

---

## 高严重度

### H-1 `/ai/chat` 与 `/ai/pdf/chat` 两条示例用 GET，实际端点只接受 POST——照抄即 405

**位置**：api-recipes.md:34（`curl "$BASE_URL/ai/chat?prompt=…"`，无 `-X POST`）、api-recipes.md:74（`curl "$BASE_URL/ai/pdf/chat?prompt=…"`，同）。

**描述**：两个端点在实现里都是 `@PostMapping` 且没有任何 `@GetMapping` 兜底——ChatController.java:50、PdfController.java:88。文档以裸 `curl URL`（GET）呈现，Spring 返回 405 Method Not Allowed。这两条是文档的 Chat 与 RAG 两章的主配方，等于两章开箱即坏。

**触发条件**：按文档原样执行（curl 默认 GET），security 开关无论开/关都会 405（方法不匹配先于鉴权）。

**修复建议**：两条示例补 `-X POST`（`@RequestParam` 从 query string 取参在 POST 下同样有效，故 `?prompt=…&chatId=…` 可保留；也可顺手改为 `-d` form 提交与 ReAct 章风格统一）。另注：`/ai/chat` 实际还支持可选 multipart `files` 参数做多模态（ChatController.java:52,62-66），配方未提及，属可选补充。

### H-2 "种子 API Key" 前提失实：`.env.example` 里没有平台 API Key，主 compose 也不注入种子

**位置**：api-recipes.md:9（`export API_KEY=<local-demo-api-key>`）、:13（"Most local examples can use the seeded API key"）、:23（"set `API_KEY` to the seeded development value from `.env.example`"）。

**描述**：三处依据共同推翻该前提：
1. `.env.example` 全文没有任何平台鉴权 API Key；唯一的 `OPENAI_API_KEY=replace_me` 是**上游 LLM 供应商凭据**，拿去当 `X-API-Key` 用必然 401；
2. 仓库提交的演示 key 已被 Flyway V15 吊销（BootstrapApiKeyInitializer.java 注释明确"Repository-committed demo keys were revoked by Flyway V15"），任何"提交在库里的 demo key"都不存在；
3. 现行唯一种子机制是启动时 `APP_BOOTSTRAP_API_KEY`（BootstrapApiKeyInitializer.java:26-31，未配置则直接跳过、不播种）；该变量**只有 docker-compose.lite.yml:32 注入**，文档指向的默认 `docker-compose.yml` 全文无此变量——用主 compose 起的栈连种子都不存在。

**触发条件**：读者按"假设本地 Docker Compose 栈已运行"（api-recipes.md:3）配好环境后执行第一章——`API_KEY` 无处取值，或误取 `OPENAI_API_KEY`。

**影响**：文档所有需要鉴权的配方（除 actuator/health）第一行就失败；这是"配方"类文档最核心的可用性缺陷。

**修复建议**：改写 Common Headers 一章：明确"用 `APP_BOOTSTRAP_API_KEY` 环境变量在你自己的 `.env` 里设定 bootstrap key（仅 lite compose 注入；主 compose 需自行补该变量）"，删除"from `.env.example`"的错误指向，并显式提醒 `OPENAI_API_KEY` 是上游模型凭据、不能作为平台鉴权 key。

---

## 中严重度

### M-1 `TENANT_ID=default` 与 bootstrap 种子的默认租户 `public` 冲突，换发 token 必失败

**位置**：api-recipes.md:10（`export TENANT_ID=default`）。

**描述**：bootstrap key 的默认租户是 `public`（BootstrapProperties.java：`tenantId = "public"`），lite compose 只注入 `APP_BOOTSTRAP_API_KEY`、未设 `APP_BOOTSTRAP_TENANT_ID`（docker-compose.lite.yml:32），所以种子 key 归属 `public` 租户。文档却让 `TENANT_ID=default`，而文档自己声明"header must match the API key during token exchange"——执行 `/auth/token` 时命中 AuthController.java:63-64，返回 `ok=0 "tenant mismatch for api key"`。

**触发条件**：用默认 `TENANT_ID=default` + 按 H-2 修复后的 bootstrap key 调 `/auth/token`。直接带 `X-API-Key` 的调用不受影响（key 租户权威、头被忽略），故只断 JWT 链路。

**修复建议**：`TENANT_ID` 默认值改为 `public`，或明确写"须与 `APP_BOOTSTRAP_TENANT_ID`（默认 `public`）一致"。

### M-2 `/actuator/prometheus` 被当作无凭据端点展示，实际要求 `PERM_METRICS_READ`/`ROLE_ADMIN`/`ROLE_OPS`

**位置**：api-recipes.md:117-120（两条裸 curl 并列，均不带任何鉴权头）。

**描述**：`/actuator/health`（与 `/info`）确实 permitAll（SecurityConfiguration.java:51），但 `/actuator/prometheus` 在 security enabled（默认 `APP_SECURITY_ENABLED=true`）时要求 `PERM_METRICS_READ`、`ROLE_ADMIN` 或 `ROLE_OPS`（SecurityConfiguration.java:53）。照抄第二条 curl 得到 401。另注 `management.endpoint.health.show-details: when_authorized`（application.yml:85）——裸 curl 只见聚合状态，拿不到组件明细，作为排障入口提示不足。

**触发条件**：security enabled（默认）下执行 api-recipes.md:119。

**修复建议**：prometheus 示例补 `X-API-Key` 头并注明需要 ADMIN/OPS 角色；health 示例可加一句"组件明细需鉴权（show-details: when_authorized）"。

### M-3 JWT 流程只教了一半：换了 token 却从未演示如何使用，失败时还会被静默吞掉

**位置**：api-recipes.md:23-29。

**描述**：
1. 文档说"For JWT-based calls … exchange the API key first"，但 exchange 之后的**所有**示例仍只带 `X-API-Key` 头，全文没有一处 `Authorization: Bearer $TOKEN`，也没把响应里的 `token` 存进变量——读者无法从文档得知 JWT 该怎么用；
2. `/auth/token` 的失败响应是 **HTTP 200 + `ok=0`**（AuthController.java:63-64 与 :104 返回 builder 而非异常状态码），示例无 `ok` 字段检查，租户不匹配/无效 key 的失败会被 curl 的退出码掩盖——正好叠加 M-1 的必败场景，用户看到的只是"成功执行了 curl"。

**触发条件**：读者想走 JWT 链路（例如要测 refresh/过期语义）时按文档操作。

**修复建议**：exchange 示例后补 `TOKEN=$(… | jq -r '.token')` 与一条带 `-H "Authorization: Bearer $TOKEN"` 的后续调用示例；并加一行"检查响应 `.ok == 1`，租户不匹配时返回 200 + ok=0"。

---

## 低严重度

### L-1 `/audit/logs`（及 `/ingestion/jobs*`、`/ai/react/*`）的权限要求未标注

**位置**：api-recipes.md:110-113（audit）、:48-69（ingestion）、:86-101（react）。

**描述**：这些端点分别要求 `PERM_AUDIT_READ`/`ROLE_ADMIN`/`ROLE_OPS`（SecurityConfiguration.java:54）、`PERM_INGESTION_READ|WRITE`（:67-68）、`PERM_CHAT_WRITE`（:59）。bootstrap 种子是 `ROLE_ADMIN`（BootstrapProperties.java 默认 role）所以**默认能通**，但读者换用自建的最小权限 key 时会 403 而无从得知原因。配方类文档通常应标注每条的最小权限。

**修复建议**：各节加一行权限标注（如 `> Requires PERM_AUDIT_READ or ADMIN`）。

### L-2 未提示限流与预算治理对配方执行的影响

**位置**：全文（`.env.example:31-34` `APP_RATE_LIMIT_ENABLED=true` 默认开启；主 compose 未关成本治理，lite 才显式 `APP_COST_GOVERNANCE_ENABLED=false`）。

**描述**：`/auth/token` 走 RateLimitFilter（令牌桶 100 容量），`/ai/chat`、`/ai/react/chat` 等在成本治理开启时会做 `assertBudget` 预算断言（ChatController.java:117）——预算不足时配方突然 4xx，文档没有任何提示，排障者容易误判为鉴权问题。

**修复建议**：文末或 Common Headers 加一条"本地栈默认启用限流与租户预算断言；429/预算类 4xx 请检查 `APP_RATE_LIMIT_*` 与租户预算"。

### L-3 `.env.example` 与主 compose 的数据库名不一致，易加重排障困惑（文档外圈但被其引用）

**位置**：api-recipes.md:3、:23 引用的本地环境本身——`.env.example:2`（`iqk_platform`）与 docker-compose.yml:11（`knowledgeops_agent`）。

**描述**：文档没有直接错，但它让读者同时参考 `.env.example` 与 compose 栈，两者 DB 库名、种子机制（前者无 bootstrap key、后者主文件也不注入）都不一致。文档若继续引用这套环境，宜注明"以 docker-compose.yml/lite.yml 为准，`.env.example` 面向裸机部署"。

**修复建议**：一句话澄清两套环境的适用范围，避免读者混用（H-2/M-1 修复时应一并处理）。

---

## 修复优先级建议

1. **H-1**（两处补 `-X POST`）与 **H-2**（重写种子 key 说明）是文档可信度的底线，先修——前者一行改动，后者决定读者第一步能否走通。
2. **M-1 / M-2 / M-3** 同属"第一步链路"问题，建议与 H-2 同一批改写 Common Headers 与 JWT 小节。
3. **L-1 / L-2 / L-3** 可作为一次性的"权限与环境差异标注"补充，顺手完成。
