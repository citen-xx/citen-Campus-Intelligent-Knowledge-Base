# Spring AI → Python LangChain Agent 迁移计划（四步）

> 本文档为跨对话窗口执行迁移的提示词底稿。每个提示词都是自包含的：包含背景、必须阅读的文件、任务、硬约束和完成标准。按顺序执行，每步验收后再进入下一步。

## 架构决策（2026-08-16 确定）

- Python（FastAPI + LangChain，暂不用 LangGraph）作为"大脑"：负责 RAG 检索、提示词组装、LLM 流式、受控 Function Calling。
- Java 作为"门户 + 权威"：保留 JWT 认证、限流、确定性规则路由（ChatIntentRouter）、身份解析（SecurityContext → userId → Student → studentId）、成绩/余额的 MySQL 查询、公开答案缓存、会话记忆（RedisChatMemory）、SSE 生命周期。
- 核心安全模型不变：**工具只由 Python 选择、由 Java 执行**。Python 工具通过携带 Java 签发的短时 callback token 回调 Java 内部接口查数据，绝不直接连 MySQL，绝不接受客户端或模型传入的 studentId。
- 文档导入管线（Tika 解析、切片、Embedding、Redis VectorStore 写入）留在 Java；Python 只做查询向量化 + 检索，查询与导入必须使用同一 Embedding 模型。
- 遵守仓库既有原则：不产出虚假数字、不做静默降级、不为堆关键词引入 LangGraph/Kafka 等。

## 四步总览

| 步骤 | 内容 | 主要产出 |
|---|---|---|
| 1 | Python 侧最小 RAG 服务（公开问答） | `ai-agent/` 目录：FastAPI + LangChain，对 Java 同一 RediSearch 索引检索并流式回答 |
| 2 | Java 公开问答代理到 Python | 公开链路走通，缓存/记忆/限流/SSE 生命周期全留在 Java |
| 3 | 学生频道工具回调安全模型 | Python bind_tools 选择工具，Java callback token + 内部接口执行查询 |
| 4 | 评测/指标/文档/依赖清理收尾 | 评测脚本指向新链路、指标口径对齐、文档更新、聊天侧 Spring AI 残留清理 |

## 提示词底稿

### 步骤 1：Python 侧最小 RAG 服务

```text
背景：本仓库（你选择的文件夹，根目录 RuoYi-Vue）是一个校园智能知识库问答平台，技术栈为 RuoYi-Vue + Spring Boot 3 + Spring AI。项目已决定：把 Spring AI 的"检索 + 生成"编排迁移到 Python（FastAPI + LangChain），Java 保留认证、限流、路由、缓存和身份解析。本步是四步迁移的第一步，只做公开知识问答的 Python RAG 服务，Java 代码一律不动。

开工前必须先读以下文件（不要凭猜测）：
1. README.md —— 主链路描述与项目约束原则
2. docs/IMPLEMENTATION_REPORT.md 和 docs/architecture.md —— 整体调用链
3. ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java —— 检索逻辑：Top-K、相似度阈值过滤、System Prompt 拼接、无足够片段时的答复策略
4. ruoyi-system/src/main/java/com/ruoyi/system/service/RagProperties.java —— RAG_CHUNK_SIZE / RAG_MIN_CHUNK_SIZE_CHARS / RAG_OVERLAP_CHARS / RAG_TOP_K / RAG_SIMILARITY_THRESHOLD 默认值与读取方式
5. ruoyi-admin/src/main/java/com/ruoyi/web/config/SpringAiRedisVectorStoreConfig.java —— 向量库 index 名、key 前缀、字段名、维度、距离度量
6. ruoyi-admin/src/main/java/com/ruoyi/web/config/WebClientConfig.java、application.yml、.env.example —— DashScope base_url、模型名、Embedding 模型名

任务：
1. 在项目根目录新建 ai-agent/ 目录，Python 3.11 + venv。依赖：fastapi、uvicorn、langchain、langchain-openai、pydantic、redis。用 requirements.txt 固定版本。
2. 实现 POST /rag/public/stream，入参 {question}：
   - 用与 Java 导入侧相同的 Embedding 模型对查询向量化（必须从 RagService.java 确认导入用的 embedding 模型；查询与导入模型不一致会导致检索失效，这是本步最大的坑）。
   - 用 redis-py 对 Java 同一 RediSearch 索引做 KNN 检索：index 名、key 前缀、字段名（content/metadata/embedding）、距离度量必须与 SpringAiRedisVectorStoreConfig 完全一致。
   - 应用与 RagService 相同的 Top-K 与相似度阈值过滤逻辑。
   - 检索片段注入 System Prompt 的语义与 Java 现状一致：模型只依据片段回答，无足够片段时明确说明信息不足，不编造。
   - 用 langchain-openai 的 ChatOpenAI 指向 DashScope OpenAI 兼容接口（base_url 与模型名与 Java 一致），流式生成。
3. SSE 输出两个事件，字段与 Java 现状对齐：answer（增量文本）和 sources（JSON 数组，字段：docId/fileName/section/chunkIndex/sourceUrl/score）。
4. 全部配置走环境变量（参考 .env.example 风格），不硬编码任何密钥，不把密钥写入文件。
5. 验证：本地启动服务，curl -N 验证 SSE 输出与 sources 事件；用真实问题对比 Python 检索结果与 Java 现状的一致性。不要产出任何命中率、延迟等数字，除非有真实运行数据。

硬约束：不引入 LangGraph / 多 Agent；不修改任何 Java 代码；向量索引 schema 必须以 Java 配置代码为准，不得猜测。
完成标准：ai-agent/ 可独立启动，对同一 RediSearch 索引完成检索 + 流式回答，SSE 事件格式与 Java 对齐，并输出一份简短的 README（启动方式、环境变量、与 Java 侧 schema 的对齐说明）。
```

### 步骤 2：Java 公开问答代理到 Python

```text
背景：本仓库（你选择的文件夹，根目录 RuoYi-Vue）是校园智能知识库问答平台（RuoYi-Vue + Spring Boot 3）。四步迁移已完成第一步：项目根目录 ai-agent/ 下已有 Python RAG 服务（FastAPI + LangChain，POST /rag/public/stream，SSE 输出 answer/sources 事件，对 Java 同一 RediSearch 索引检索）。本步把 Java 的公开知识问答流式入口改为代理到该 Python 服务，Java 保留全部横切职责。

开工前必须先读以下文件：
1. README.md 与 docs/IMPLEMENTATION_REPORT.md —— 调用链、缓存与限流约定
2. ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java —— 现有公开入口 public/stream 的实现
3. ruoyi-admin/src/main/java/com/ruoyi/web/service/ChatSessionScopeService.java —— 会话 Key 生成规范
4. ruoyi-admin/src/main/java/com/ruoyi/web/service/PublicKnowledgeCacheService.java —— 公开答案缓存（知识库版本 + 问题 SHA-256）
5. ruoyi-admin/src/main/java/com/ruoyi/web/service/SseStreamLifecycle.java —— SSE 生命周期（60 秒超时、客户端断开、Future.cancel）
6. ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java —— 会话记忆 key 规范
7. ruoyi-admin/src/main/java/com/ruoyi/web/config/WebClientConfig.java 与 application.yml —— WebClient 配置
8. ai-agent/ 目录及其 README —— Python 服务现状

任务：
1. 将 SseChatController 的公开入口（public/stream）改为：限流 → Router（公开）→ 公开缓存查询 → 未命中时用 WebClient 流式调用 Python 的 /rag/public/stream，把 Python 的 SSE 事件（answer/sources）透传给前端 SseEmitter → 完成后按现有规则写入公开缓存。
2. 缓存逻辑完全保留在 Java：PublicKnowledgeCacheService 不动，key 仍为知识库版本 + 规范化问题 SHA-256，hit/miss Redis 计数器与 AiMetricsController 行为不变。缓存命中时不调用 Python。
3. 会话记忆完全保留在 Java：RedisChatMemory 的 key 规范不变（ai:chat:memory:{public|student}:{userScope}:{conversationId}）。转发时把最近 N 条历史作为入参传给 Python（若 ai-agent 尚不支持 history 入参，本步在 Python 侧补上并保持向后兼容）；响应完成后 Java 写回记忆。Python 保持无状态。
4. SSE 生命周期沿用现有机制：60 秒超时、客户端断开取消（Future.cancel(true)）、异常与完成事件，行为与现状一致；新增"Python 服务不可用"时明确发送 SSE error 事件，不允许静默降级或假装成功。Python 服务地址与超时参数走环境变量（如 PYTHON_AGENT_BASE_URL）。
5. Micrometer 指标：campus.ai.chat.complete（端到端）保留在 Java；campus.ai.rag.retrieval / campus.ai.chat.first-token / campus.ai.llm.stream 已转移到 Python 侧，本步不迁移，在代码注释和本步交付说明中标记"待第四步统一口径"。
6. 学生频道（student/stream）本步完全不动，保持现状可运行。
7. 验证：mvn -DskipTests compile 通过；在真实环境（MySQL/Redis/RediSearch/Python 服务）下用 curl 验证公开入口的 SSE 事件、缓存命中的二次请求不再调用 Python（可用 Python 侧日志确认）、断连时 Java 正确取消。不产出任何性能/命中率结论，除非有真实运行数据。

硬约束：不改变限流、会话隔离、缓存 key 与知识库版本失效机制的语义；不删除 Spring AI 依赖（文档导入管线仍在使用）；不改前端；不提交 git。
完成标准：公开问答完整链路走通（前端行为无感变化），缓存 hit/miss 计数真实，SSE 生命周期测试通过，学生频道不受影响。
```

### 步骤 3：学生频道工具回调安全模型

```text
背景：本仓库（你选择的文件夹，根目录 RuoYi-Vue）是校园智能知识库问答平台（RuoYi-Vue + Spring Boot 3）。四步迁移已完成前两步：Python RAG 服务（ai-agent/）已上线，Java 公开问答已代理到 Python。本步把学生频道接入 Python 的受控 Function Calling。核心原则：**身份与数据访问权只归 Java**，Python 只负责"选择工具"，不负责"执行查询"。

开工前必须先读以下文件：
1. README.md 与 docs/IMPLEMENTATION_REPORT.md 第 5、6 节 —— 学生链路与隔离规范
2. ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java —— student/stream 现状
3. ruoyi-system/src/main/java/com/ruoyi/system/service/ChatIntentRouter.java —— 确定性规则路由（成绩/余额/公开/未知）
4. ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java —— 身份解析与 ToolContext 校验
5. ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java、StudentScoreRequest.java、CardBalanceRequest.java —— 现有 Tool 定义
6. ruoyi-framework/src/main/java/com/ruoyi/framework/config/SecurityConfig.java 与 security/filter/JwtAuthenticationTokenFilter.java —— 安全链
7. ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/TokenService.java —— token 签发/校验机制
8. evaluation/security-regression.mjs —— 现有安全回归用例

任务（先理解安全模型再动手）：
1. ChatIntentRouter 继续留在 Java：STUDENT_SCORE / CARD_BALANCE 规则命中仍由 Java 直接执行（不经 LLM、不经 Python），行为与现状完全一致。
2. 学生频道中不属于规则命中的问题转发到 Python，并允许模型在受控范围内调用成绩/余额工具。Python 侧用 LangChain bind_tools + Pydantic 定义：成绩工具只接收 subject；余额工具零参数。Tool Schema 中不得出现 studentId / userId 字段。
3. 核心设计——Python 的工具不查 MySQL：
   - Java 转发学生请求给 Python 时，签发一个短时（60-120 秒）、带签名、绑定 userId/studentId/conversationId 的 callback token，随请求传给 Python。
   - Python 的工具内部实现改为回调 Java 新增的内部接口（如 POST /internal/ai/tool/student-score、POST /internal/ai/tool/card-balance），请求头携带该 token。
   - Java 内部回调接口不属于 @Anonymous 公开面：校验 token 签名与过期，从 token 解析 studentId 后经现有 CurrentStudentService / Mapper 查库；任何来自请求体、模型或前端传入的 studentId 一律忽略。
4. ToolContext 一致性校验沿用现有语义（查询上下文与当前学生一致），在 Java 侧完成。callback token 的签名密钥走环境变量（如 CALLBACK_TOKEN_SECRET），不入库不入日志。
5. 权限与限流保持不变：student 角色要求、JWT 过滤、学生频道 20 次/分钟限流均不动。日志不记录成绩、余额、完整 token 或完整 ToolContext。
6. 测试与验证：更新 evaluation/security-regression.mjs 或新增用例，覆盖——匿名请求被拒、伪造 studentId 不改变登录身份、callback token 过期/篡改被拒、跨 conversationId 不串数据、规则命中直达不经过 LLM（可用日志确认）。mvn -DskipTests compile 通过；真实环境（两个学生账号 Token）联调通过全部安全用例。
7. 不产出任何安全/性能结论数字，除非有真实运行数据。

硬约束：绝对不允许 Python 直接连接 MySQL；绝对不允许把 studentId 放进工具 Schema、日志或由客户端传入；不引入 LangGraph；不提交 git。
完成标准：学生频道在 Python 联动下功能与安全行为与现状一致，全部安全回归用例通过。
```

### 步骤 4：收尾——评测、指标、文档、依赖清理

```text
背景：本仓库（你选择的文件夹，根目录 RuoYi-Vue）是校园智能知识库问答平台（RuoYi-Vue + Spring Boot 3）。四步迁移已完成前三步：Python RAG 服务（ai-agent/）上线、Java 公开问答代理到 Python、学生频道工具回调安全模型落地（Java 签发 callback token、Python 工具回调 Java 内部接口执行查询）。本步收尾：评测脚本迁移、指标口径对齐、文档更新、聊天侧 Spring AI 残留清理、全量验证。

开工前必须先读以下文件：
1. README.md、docs/IMPLEMENTATION_REPORT.md、docs/architecture.md —— 现有文档基线
2. docs/python-agent-migration-plan.md —— 四步迁移的架构决策与安全模型
3. evaluation/README.md、evaluation/security-regression.mjs、evaluation/performance-benchmark.mjs —— 评测脚本现状
4. ruoyi-admin/src/test/java/com/ruoyi/evaluation/RagEvaluationRunnerTest.java —— 命中率评测器
5. ai-agent/README.md —— Python 服务现状
6. interview-prep/project-brief.md —— 面试材料（注意其中引用的 AiController.java 等文件可能已被删除，需按现状修正）

任务：
1. 评测迁移：RagEvaluationRunnerTest 改为调用 Python 检索服务（或在 ai-agent 侧提供等价评测脚本），HitRate@1/@3/@5 语义不变；evaluation/data/ground-truth.json 正式数据为空时评测器必须失败，不得输出 0% 或推导任何数字。security-regression.mjs、performance-benchmark.mjs 指向新链路（首 Token 耗时取 SSE 首个 answer 事件）。所有数字只来自真实运行。
2. 指标对齐：Python 侧暴露 campus.ai.rag.retrieval / campus.ai.llm.stream / campus.ai.chat.first-token（Prometheus 或轻量 /metrics JSON 均可）；Java 保留 campus.ai.chat.complete 与缓存 hit/miss Redis 计数器；在文档中明确每个指标的采集位置。
3. 文档更新：README.md（双服务架构、启动方式、新增环境变量如 PYTHON_AGENT_BASE_URL、CALLBACK_TOKEN_SECRET）；docs/architecture.md 的 Mermaid 图更新为 Java 门户 + Python Agent；新增 docs/python-agent-migration.md（迁移决策、安全模型、指标映射、运维说明）；interview-prep/project-brief.md 中过时引用按现状修正，保持"事实/推断/需要本人补充"的证据等级风格。
4. 依赖清理：确认 Spring AI 仅剩文档导入管线（Tika 解析、TokenTextSplitter、Embedding、Redis VectorStore 写入）使用后，移除聊天侧残留代码（如不再使用的 ChatClient 配置、EduAiFunctionConfig 若已被 callback 方案替代）；spring-ai 依赖本身保留。清理不得引入回归。
5. 全量验证：mvn -DskipTests compile；ai-agent 启动脚本与说明；README 构建与运行章节按真实步骤修正；真实环境跑一次端到端验证（公开问答、学生成绩、余额、缓存、限流、SSE 中断、安全回归脚本）。
6. 遵守仓库既有原则：不产出虚假数字、不做静默降级、不引入 LangGraph/Kafka 等新关键词、真实凭据不入库、不提交 git（除非用户明确要求）。

完成标准：双服务架构文档完整且可照做启动；评测与指标可运行且口径清晰；代码无聊天侧 Spring AI 残留；端到端验证与安全回归通过。
```

## 使用方式

每个步骤在一个新的对话窗口执行：选择同一项目文件夹 → 粘贴对应提示词 → 让新窗口先读文件再动手 → 按"完成标准"验收 → 再开下一步。
