# Python Agent 迁移完成说明

本文记录 Java 聊天链路迁移到 `ai-agent/` 后的最终架构、评测口径、安全模型、指标映射和运维步骤。设计过程与分步决策见 `docs/python-agent-migration-plan.md`。

## 迁移结果

迁移采用“Java 门户 + Python Agent”双服务，而不是把认证与业务数据一起搬到 Python：

- Java 保留 Web 入口、JWT/角色、Redis 限流、会话 Scope、公开缓存、文档管理、callback token 和 MySQL 查询。
- Python 接管公共/学生 RAG 检索、Prompt、模型生成和学生频道白名单工具选择。
- Java 文档导入仍使用 Spring AI Tika、`TokenTextSplitter`、Embedding 和 Redis VectorStore，确保既有索引写入 schema 不变。
- Java 聊天代码已移除 `ChatClient`、Spring AI Function Calling、`ToolContext` 和 Spring AI `ChatMemory`；会话 Redis JSON 继续使用兼容的 `type/text` 格式。
- Python/模型/向量服务故障会显式失败，不回退到已经删除的 Java 聊天实现。

## 接口映射

| 调用方 | 接口 | 用途 |
|---|---|---|
| Browser -> Java | `POST /api/ai/chat/public/stream` | 公开 SSE，含限流、缓存、会话 |
| Browser -> Java | `POST /api/ai/chat/student/stream` | 登录学生 SSE |
| Java -> Python | `POST /rag/public/stream` | 公共检索 + 流式生成 |
| Java -> Python | `POST /rag/student/stream` | 学生检索 + 白名单工具选择 |
| Evaluation -> Python | `POST /rag/retrieve` | 只执行真实 Top-K 检索，不调用聊天模型 |
| Python -> Java | `POST /internal/ai/tool/student-score` | callback 成绩查询 |
| Python -> Java | `POST /internal/ai/tool/card-balance` | callback 余额查询 |
| Operator -> Python | `GET /metrics` | Python 进程内真实计时数据 |

`/rag/retrieve` 接收 `question` 和 `topK`，返回经过相似度阈值过滤的有序 `sources`。它与问答使用同一个 Embedding 模型、RediSearch schema、分数换算和阈值，因此 HitRate 评测不再经过 Java Retriever。

## callback 安全模型

1. 学生请求必须通过 Java JWT 认证并具有 `student` 角色。
2. Java 从 `SecurityContext` 取 `userId`，再从 MySQL 解析当前 `Student.studentId`；请求体和模型输出都不是身份来源。
3. 非确定性学生问题转发前，Java 签发 HS512 callback token，绑定 `userId`、`studentId`、scoped `conversationId`、签发时间和过期时间。
4. token 有效期被 Java 限制为 60-120 秒。`CALLBACK_TOKEN_SECRET` 只存在 Java 配置中，不提供给 Python。
5. Python 工具 schema 只暴露 `subject` 或空对象，并携原 token 和 conversation header 回调 Java。
6. Java 验证签名、过期、subject、conversationId，再用 token 的 `userId/studentId` 与数据库当前绑定复核。
7. 公开入口不签发 callback token，Python 不连接 MySQL。

确定性命中的成绩/余额请求在 Java 内直接调用 `StudentBusinessToolService`，使用同一个服务端解析出的 `studentId`。callback 与直达路径共享课程白名单和结构化返回逻辑。

## 指标映射

| 指标 | 服务 | 实现与口径 |
|---|---|---|
| `campus.ai.rag.retrieval` | Python | `PublicRagService.retrieve`；包含查询 Embedding 和 RediSearch KNN |
| `campus.ai.llm.stream` | Python | 公共/学生模型生成从开始到结束、异常或取消 |
| `campus.ai.chat.first-token` | Python | 模型生成开始到首个非空回答片段 |
| `campus.ai.chat.complete` | Java | 公开入口端到端，包含缓存命中或完整 Python 代理 |
| `campus.ai.llm.call` | Java | 学生频道 Python 调用整体时间，按 outcome 区分 |
| `campus.ai.tool.query` | Java | Java 确定性成绩/余额查询 |
| cache hit/miss | Java + Redis | `PublicKnowledgeCacheService.get` 的真实结果 |

Python `GET /metrics` 返回每项的 `count`、`totalSeconds`、`averageSeconds`、`maxSeconds`。无样本不计算平均值或最大值。该注册表是单进程内存态：多 worker 部署需要由采集系统分别抓取实例并聚合，进程重启后重新计数。

Java Timer 使用 Actuator `/actuator/metrics/{metric.name}`。缓存使用 Redis 持久计数器，通过具备知识库管理权限的 `/system/ai/metrics/cache` 查看；无请求时 `hitRate=null`。

性能脚本的首 Token 口径是 Java SSE 客户端收到第一个 `answer` 事件的耗时，包含 Java 入口、网络、Python 检索、模型首片段和代理传输；它与 Python 内部 `campus.ai.chat.first-token` 的边界不同，报告时不得混用。

## RAG 评测口径

`RagEvaluationRunnerTest` 读取 `evaluation/data/ground-truth.json`，逐条调用 Python `/rag/retrieve` 取 Top-5。对 `K=1/3/5`：前 K 个实际来源中，只要一个来源与任一人工标注的 `docId` 相同，并且标注了 `section` 时章节也相同，该问题即命中。

```text
HitRate@K = 命中问题数 / 有效人工标注问题总数
```

以下情况在任何网络调用和结果写入前直接失败：`samples` 缺失/为空、存在 `_example` ID、问题或 expectedSources 缺失、expected source 没有 `docId`。仓库当前正式文件为空，因此不能产生或引用命中率。

## 部署与启动顺序

1. 启动 MySQL、Redis Stack/RediSearch，并确认 Java 导入使用的索引存在或允许 Java 初始化。
2. 配置 Java 和 Python 共用的 `DASHSCOPE_API_KEY`、Embedding 模型、Redis URI、index 和 prefix；两端值必须一致。
3. 配置 Java 的 `CALLBACK_TOKEN_SECRET` 和 Python 的 `JAVA_CALLBACK_BASE_URL`。生产环境只允许 Python 网络身份访问 `/internal/ai/tool/**`，callback token 是应用层第二道校验。
4. 在 `ai-agent/` 创建 `.venv`、安装 `requirements.txt`，运行 `start.ps1` 或 `start.sh`。
5. 访问 Python `/metrics` 确认进程可达。首次 `/rag/retrieve` 或聊天请求会执行 `FT.INFO` schema 校验。
6. 配置 Java `PYTHON_AGENT_BASE_URL`，启动 `mvn -pl ruoyi-admin -am spring-boot:run`。
7. 启动 Vue，依次验证公开问答、成绩、余额、缓存、限流和 SSE 中断。

默认只在 `127.0.0.1:8090` 监听 Python。跨主机部署时应通过内网、防火墙或反向代理限制 `/rag/**` 和 `/metrics` 暴露范围，并确保 `JAVA_CALLBACK_BASE_URL` 指向 Python 可访问但非公网任意可达的 Java 地址。

## 验证命令

```powershell
mvn -DskipTests compile
mvn -DskipTests test-compile
node --check evaluation/security-regression.mjs
node --check evaluation/performance-benchmark.mjs
```

Python 环境：

```powershell
cd ai-agent
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

真实安全回归与性能运行见 `evaluation/README.md`。若缺少真实 token、已知成绩/余额、非空 workload、MySQL、Redis Stack、OSS 或模型连接，应报告为“未运行/环境不满足”，不得把静态检查当成端到端通过。

## 回滚与故障处理

- Python 不可达：Java SSE 返回明确错误；检查 `PYTHON_AGENT_BASE_URL`、Python 日志和连接超时，不启用聊天降级。
- schema 校验失败：核对两端 `VECTOR_INDEX_NAME`、`VECTOR_KEY_PREFIX`、Embedding 维度和 Java metadata schema；不要让 Python重建索引掩盖配置错误。
- callback 401/403：核对 token 时钟、Java secret、conversation header、TTL 和数据库身份绑定，不记录完整 token。
- 模型首片段存在但 Java 客户端未收到：同时查看 Python first-token、LLM stream 和 Java complete，再检查代理缓冲与客户端中断。
- 缓存异常：查看 Redis hit/miss 与知识库版本；学生请求不应改变公开缓存计数。

旧 Java 聊天实现已经删除，回滚必须回到迁移前的受控代码版本，不能在运行时静默切换。
