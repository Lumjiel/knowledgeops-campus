# 部署手册 · 云服务器（lite 版）

> 适用：2 核 4G 云服务器，Docker + Docker Compose 已装。
> 本仓库 `docker-compose.lite.yml` 已就绪：app + mysql + web 三服务，向量库 simple、队列 db_polling、内存预算 app 1536m / mysql 768m / web 128m。
> 本机（开发机）无 Docker，属预期状态；本文件就是留给服务器阶段照着执行的。

## 一、准备

```bash
git clone <本仓库地址> && cd knowledgeops-agent   # 或 scp 整个目录
cat > .env <<'EOF'
OPENAI_API_KEY=sk-xxxx                 # 必填，模型密钥
OPENAI_BASE_URL=https://xxx/v1         # OpenAI 兼容网关
MYSQL_PASSWORD=改一个强密码
APP_CORS_ALLOWED_ORIGINS=http://<服务器IP>
EOF
chmod 600 .env    # .env 已在 .gitignore（第 61-62 行），不会入库
```

## 二、启动与验收（spec-D5 七条，逐条留输出）

```bash
# 1. 启动，期望退出码 0
docker compose -f docker-compose.lite.yml up -d; echo "EXIT=$?"

# 2. 三服务全 Up（app 有 Dockerfile 内置 healthcheck，首次启动含构建，等 3-5 分钟）
docker compose -f docker-compose.lite.yml ps

# 3. 健康检查，期望含 "status":"UP"
curl -s localhost:8080/actuator/health

# 4. 前端标题，期望 ≥ 1
curl -s localhost/ | grep -c "校园规章问答"

# 5. 内存预算，期望 app < 1.5G
docker stats --no-stream --format "{{.Name}} {{.MemUsage}}"

# 6. 真实问答，期望 JSON 含 answer 且 citationCards 非空（需先在前端「鉴权」卡片用演示 API Key 换 JWT，或直接带 X-API-Key；chatId 用 campus-rules，与种子语料一致）
curl -s -X POST localhost:8080/api/react/chat -H 'Content-Type: application/json' \
  -H "Authorization: Bearer <JWT或X-API-Key: 演示密钥>" \
  -d '{"message":"旷课50学时怎么处分"}'

# 7. 坑 1 反向验证：重启后索引仍在（simple 向量快照挂在 vector-data 卷）
docker compose -f docker-compose.lite.yml restart app
# 等健康检查恢复后重跑第 6 条，仍返回 citationCards 即通过
```

## 三、种子语料说明（SeedDataRunner）

- 首次启动时 `config/SeedDataRunner.java` 检查索引快照 `/data/vector-store/chat-pdf.json`，不存在则把 `demo-data/`（只读挂载到 `/data/seed`）下所有 PDF 同步导入，日志前缀 `[seed]`。
- 换语料：替换 `demo-data/` 下的 PDF 后 `docker compose down && docker volume rm knowledgeops-agent_vector-data`（库名前缀以 `docker volume ls` 实际输出为准）再 `up -d`，会重新导入。
- **chatId 约定**：检索按 `tenant_id + chat_id` 过滤（VectorRetriever 硬过滤），种子固定写入 `tenant=public, chatId=campus-rules`（可用 `APP_SEED_CHATID`/`APP_SEED_TENANTID` 覆盖）。**演示时前端会话必须使用同一个 chatId**，否则检索为空。

## 四、安全底线（spec-D6 对照）

| 项 | 做法 |
|---|---|
| 上传接口 | lite 部署不开放：演示用 API Key 若含 `PERM_INGESTION_WRITE`，用 `POST /auth/api-keys/revoke` 吊销后换发无该权限的 Key（AuthController 已支持）；另确认网关层不转发 `/api/ingestion/upload` |
| 限流 | Bucket4j 已对 `/api/react/chat` 生效（tenant+ip 复合维度，RateLimitFilter） |
| MySQL | lite compose 未映射端口，仅容器内网可达 |
| 密钥 | 全走 `.env`；`.gitignore` 已排除 `.env`/`.env.*` |
| 日志 | 应用日志自带脱敏（MaskingUtils：API Key/邮箱/参数），不要把 `.env` 内容贴进日志 |

## 五、失败排查速查

| 症状 | 先查 |
|---|---|
| app 起不来，日志见 Flyway/连接错误 | `docker compose -f docker-compose.lite.yml logs mysql`——MySQL 未 healthy 前 app 不会启动，若反复重启查 `.env` 里 `MYSQL_PASSWORD` 是否与首启一致（首启后改密码需重建 mysql-data 卷） |
| 第 6 条 citationCards 为空 | chatId 是否用了 `campus-rules`；`docker compose logs app \| grep "\[seed\]"` 看导入是否成功 |
| 重启后回答质量突变/检索空 | 第 7 条没过说明 volume 没挂对：`docker volume inspect knowledgeops-agent_vector-data` |
| web 502 | app healthcheck 未通过（看 `docker compose ps` 的 health 列），或 nginx 转发目标 `app:8080` 不可达 |

---

## 六、实战复盘 · 实际踩的六个坑（2026-10-30）

> spec-D 预判了 3 个坑（volume / MySQL ready / 预置语料），实际部署踩了 6 个。
> 以下全部是**真实发生并已修复**的，按暴露顺序。

### 坑 1 · Flyway V17 迁移必然失败（上游 bug）

```
Migration V17__course_school_tenant_isolation.sql failed
SQL State: 42000  Error Code: 1091
Can't DROP 'city'; check that column/key exists
```

V17 最后一行 `ALTER TABLE school DROP INDEX city;` 的注释写着「no-op if the old index does not exist」，
但 **MySQL 的 DROP INDEX 不是 no-op**——索引不存在直接报 1091。该迁移在任何干净库上都必然失败。

已改为先查 `information_schema.statistics` 再动态执行（无索引走 `DO 0`）。

### 坑 2 · `.env` 里有 ≠ 容器能看到

`app.security.jwt-secret must not be blank` —— `.env` 里有 `APP_JWT_SECRET`，但 compose 的
`environment` 段没透传。**新增任何环境变量都必须同时改 compose。**

连带：prod profile 会拒绝 `spring.rabbitmq` 的 `guest/guest` 默认值，**即使这个部署不用 MQ**。
需给 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 任意非 guest 值。

### 坑 3 · health 一直 DOWN，web 永远起不来（最耗时）

lite 版砍掉了 redis / rabbitmq，但 `RedisReactiveHealthIndicator` 和 `RabbitHealthIndicator`
还在跑，连不上就报 DOWN；web 因 `depends_on: service_healthy` 一直等着。

```yaml
- MANAGEMENT_HEALTH_REDIS_ENABLED=false
- MANAGEMENT_HEALTH_RABBIT_ENABLED=false
- MANAGEMENT_TRACING_ENABLED=false   # 顺带关掉每秒刷的 "Failed to export spans"
```

排查技巧：`show-details` 默认 `when_authorized`，看不到明细。临时加
`MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always` 就能看到哪个 component 挂了。

### 坑 4 · 前端标题没改

只改了 `App.vue` 的品牌文案，漏了 `frontend/index.html` 的 `<title>`。

### 坑 5 · 向量快照写不进卷

语料导入 `SUCCEEDED` 但 `/data/vector-store/` 是空的：

```
Creating new vector store file: /data/vector-store/chat-pdf.json.tmp
WARN  Failed to persist SimpleVectorStore snapshot
```

卷是 `root:root`，容器以 `uid=100(appuser)` 运行。修法：

```bash
docker run --rm -v knowledgeops_vector-data:/v alpine chown -R 100:101 /v
```

### 坑 6 · ReAct 缺 CONVERSATION_ID（最隐蔽）

**现象**：所有问答返回「当前规划器暂不可用」，`fallback: true`，但 `/ai/chat` 普通对话正常。

**真正异常**（被 `catch (RuntimeException)` 吞掉，必须加日志才看得到）：

```
IllegalArgumentException: conversationId cannot be null
```

**根因**：`ReactAgentService.routedPrompt()` 没传 ChatMemory 的会话 ID，
而 `ChatController` 是传了的（`.advisors(a -> a.param(CONVERSATION_ID, conversationId))`）。

**修法**：`routedPrompt(decision, conversationId)` 加 `.advisors(...)`，
并让 `conversationId` 透传进 `callModel` / `callModelStream`。

---

## 七、运维速查（补充）

### 改代码后的正确姿势

```bash
# 只改配置/环境变量 → 不重建
docker compose -f docker-compose.lite.yml up -d --no-deps app

# 改了 Java 代码 → 必须重建（约 1 分钟，Maven 缓存命中）
docker compose -f docker-compose.lite.yml build app
docker compose -f docker-compose.lite.yml up -d --no-deps app
```

### 三个容易再踩的坑

1. **旧 FAILED 的导入任务无法重新认领** —— `processQueuedJob` 只接受 `PENDING`/`RETRY`：
   ```sql
   UPDATE ingestion_job SET status='PENDING', error_message=NULL;
   ```
2. **Checkstyle 有 500 行文件上限** —— 改大文件前先 `wc -l`，超了构建直接失败。
3. **不要用 `docker compose run` 做临时验证** —— 超时会留下孤儿容器占内存。

### 当前实测基线

| 项 | 值 |
|---|---|
| 内存 | app 366M + mysql 166M + web 3.6M = **536 MiB** |
| 首页响应 | ~67ms |
| 语料快照 | 2.9M（`chat-pdf.json`） |
| 演示 API Key | 见 `.env` 的 `APP_BOOTSTRAP_API_KEY`，2026-11-29 到期 |
