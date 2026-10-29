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
