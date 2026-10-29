# 规格 D · 部署到云服务器

> 给执行者读。目标：一条命令起服务，能发链接给面试官。
> 前置：A 通过验收（前端能 build）。

---

## D0 · 硬约束

- **不改业务逻辑**，只改部署配置
- 不改 `src/main/java` 下任何 `.java`（除非修部署才暴露的 bug，且必须写进 `BLOCKED.md`）
- **不开放文件上传接口**（省内存 + 防滥用，演示够用）
- API Key / 数据库密码**只走环境变量**，不许写进代码或 yml 明文
- 目标机器：2 核 4G，Docker + Docker Compose 已装

---

## D1 · 现状

```
docker-compose.yml      已有 6 个服务：app / mysql / redis / rabbitmq / tempo / web
Dockerfile              已有
frontend/Dockerfile     已有（nginx 托管静态资源）
frontend/nginx.conf     已有
```

**要做的不是从零写，是「砍到能跑」。**

---

## D2 · 精简 compose

新建 `docker-compose.lite.yml`（**新建，不改原文件**），只保留：

```yaml
services:
  app:
    build: .
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - APP_VECTOR_STORE_BACKEND=simple          # 关键：省掉 pgvector
      - APP_REQUIRE_PGVECTOR=false
      - APP_SIMPLE_VECTOR_STORE_PATH=/data/vector-store/chat-pdf.json
      - RAG_RETRIEVE_TOP_K=12
      - RAG_RERANK_TOP_K=6
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - OPENAI_BASE_URL=${OPENAI_BASE_URL}
      - MYSQL_HOST=mysql
      - MYSQL_PASSWORD=${MYSQL_PASSWORD}
    volumes:
      - vector-data:/data/vector-store     # ← 关键，见 D3
    ports: ["8080:8080"]
    depends_on: [mysql]
    mem_limit: 1536m

  mysql:
    image: mysql:8.0
    environment:
      - MYSQL_ROOT_PASSWORD=${MYSQL_PASSWORD}
      - MYSQL_DATABASE=knowledgeops
    volumes:
      - mysql-data:/var/lib/mysql
    mem_limit: 768m

volumes:
  vector-data:
  mysql-data:
```

**要点**：
- 砍掉 redis / rabbitmq / tempo → 队列用 `NoopIngestionQueue` 或 `db_polling`（看现有配置支持哪个）
- 向量库切 `simple`，不装 pgvector
- 两个 volume 必须挂，否则重启丢数据

---

## D3 · 三个必踩的坑（提前写给你）

### 坑 1 · SimpleVectorStore 重启就丢索引

`IngestionService.persistSimpleVectorStoreIfNeeded()` 把索引快照写到 `./data/vector-store/chat-pdf.json`（本地路径）。**容器重启后容器内文件系统重置，索引全丢。**

所以：`APP_SIMPLE_VECTOR_STORE_PATH` 必须指到挂载的 volume（上面已配 `/data/vector-store/chat-pdf.json`）。

### 坑 2 · MySQL 首次启动要等，app 会先挂

`depends_on` 只保证容器启动顺序，不保证 MySQL ready。要么加 healthcheck + `condition: service_healthy`，要么给 app 配重试。**必须处理，否则第一次 `up` 必失败。**

### 坑 3 · 预置语料怎么进去

不开放上传，那语料怎么进库？两条路：
- **A（推荐）**：启动时用一个 `CommandLineRunner` 检查索引文件是否存在，不存在就调 `IngestionService` 导入 `demo-data/` 下预置的 PDF
- **B**：提供一个一次性脚本 `scripts/seed.sh`，在宿主机跑 `curl` 调内部导入接口

**选 A**，但代码改动要最小：新增一个 `config/SeedDataRunner.java`（新文件，允许），条件 `@ConditionalOnProperty(name="app.seed.enabled", havingValue="true")`。

---

## D4 · 前端

现有 `frontend/Dockerfile` + `nginx.conf` 已能托管静态资源。两种接法，**选简单的**：

- 方案 1：`frontend` 单独一个服务，nginx 反代 `/api` 到 `app:8080`
- 方案 2：把 `npm run build` 产物拷进 Spring Boot 的 `src/main/resources/static/`

**选方案 1**（不动后端打包逻辑）。在 compose 里加：

```yaml
  web:
    build: ./frontend
    ports: ["80:80"]
    depends_on: [app]
```

`nginx.conf` 里确保 `/api` 转发到 `http://app:8080`。

---

## D5 · 验收

### 机器可判（在服务器上跑）

| # | 命令 | 期望 |
|---|---|---|
| 1 | `docker compose -f docker-compose.lite.yml up -d` | 退出码 0 |
| 2 | `docker compose -f docker-compose.lite.yml ps` | app / mysql / web 三个 `Up` |
| 3 | `curl -s localhost:8080/actuator/health` | 含 `"status":"UP"` |
| 4 | `curl -s localhost/ \| grep -c "校园规章问答"` | ≥ 1 |
| 5 | `docker stats --no-stream --format "{{.Name}} {{.MemUsage}}"` | app 内存 < 1.5G |
| 6 | `curl -s -X POST localhost:8080/api/react/chat -H 'Content-Type: application/json' -d '{"message":"旷课50学时怎么处分"}'` | 返回 JSON 含 `answer` 且 `citationCards` 非空 |
| 7 | 重启验证：`docker compose restart app` 后再跑第 6 条 | 仍能返回答案（证明索引没丢） |

**第 7 条是坑 1 的反向验证**——不测这条，你不知道 volume 有没有挂对。

### 人工抽查

- 手机浏览器打开 `http://<IP>/`，能正常显示（响应式不崩）
- 截图存 `docs/screenshots/deploy.png`

---

## D6 · 安全底线

| 项 | 要求 |
|---|---|
| 上传接口 | **关闭**（`/api/ingestion/upload` 返回 403 或直接不暴露） |
| 限流 | 已有 Bucket4j，确认对 `/api/react/chat` 生效（20 次/分钟/人） |
| 数据库 | 只监听容器内网，不映射端口到宿主机 |
| 密钥 | 全部走 `.env`，`.env` 加进 `.gitignore` |
| 日志 | 不打印 API Key、不打印完整 prompt（可能含学生信息） |

---

## D7 · 禁止

- ❌ 把 API Key / 密码写进 yml 或代码
- ❌ 映射 MySQL 端口到公网
- ❌ 开放上传接口
- ❌ 改原 `docker-compose.yml`（新建 lite 版）
- ❌ 为了跑起来把测试关掉
- ❌ 在 `BLOCKED.md` 之外的地方抱怨环境问题（写进去，跳过继续做别的）
