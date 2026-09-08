# 校园智能知识库问答平台：项目速览

本文只使用三种证据等级：

- `事实`：能由当前代码、配置、测试或本次真实命令输出直接确认。
- `推断`：根据代码关系得出的合理解释，面试时应表述为设计理解而非测量结论。
- `需要本人补充`：仓库没有足够证据，需要项目本人提供真实数据或经历。

## 1. 项目定位

- `事实`：项目是 RuoYi-Vue + Spring Boot 3 的校园知识问答和学生个人业务查询平台，运行时采用 Java 门户 + Python Agent 双服务。证据：`README.md`、`docs/architecture.md`、`ai-agent/app/main.py`。
- `事实`：公开知识问答走 RAG，成绩和一卡通余额走服务端身份约束的业务工具；Python 不连接 MySQL。证据：`SseChatController.java`、`AiToolCallbackController.java`、`ai-agent/app/service.py`。
- `推断`：项目的主要工程价值不是“接了一个模型”，而是把公共知识、个人数据、身份边界、流式体验和可评测性放进同一条可运维链路。
- `需要本人补充`：真实学校/团队/用户规模、上线时间、个人负责范围和实际业务反馈，仓库不能证明。

## 2. 用户如何使用

- `事实`：公共用户调用 `POST /api/ai/chat/public/stream`，Java 完成限流、会话 Scope、缓存，再代理 Python `/rag/public/stream`。证据：`SseChatController#publicStreamChat`、`PythonPublicRagClient#stream`。
- `事实`：学生调用 `POST /api/ai/chat/student/stream`，必须登录并具有 `student` 角色。证据：`SseChatController#studentStreamChat`、`SecurityConfig.java`。
- `事实`：成绩/余额确定性意图由 Java 直接执行；其他学生问题交给 Python 白名单工具，工具回调 Java 内部接口。证据：`SseChatController#handleDirectStudentToolCall`、`PublicRagService#stream_student_answer`。
- `事实`：管理员通过 `KnowledgeDocController` 导入/替换文档，文档经 OSS、Tika、结构切分、Embedding 写入 Redis VectorStore。证据：`KnowledgeDocController.java`、`KnowledgeDocServiceImpl.java`、`RagService.java`。
- `推断`：前端是展示和请求编排层，真正的身份、缓存、检索和工具边界都由后端控制。

## 3. 技术栈与实际作用

| 技术 | 当前实际作用 | 证据等级 |
|---|---|---|
| Java 17 / Spring Boot 3.3.3 | 门户、Controller、配置和服务装配 | 事实：`pom.xml` |
| Spring Security + JWT | 登录态、角色和接口权限 | 事实：`SecurityConfig.java`、`JwtAuthenticationTokenFilter.java` |
| MyBatis + MySQL | 学生、成绩、余额和后台数据 | 事实：Mapper/XML、SQL |
| Redis | JWT/业务缓存、会话、限流计数 | 事实：`RedisChatMemory.java`、`PublicKnowledgeCacheService.java`、`RateLimiterAspect.java` |
| Redis Stack / RediSearch | 向量 JSON 索引和 KNN 检索 | 事实：`SpringAiRedisVectorStoreConfig.java`、`ai-agent/app/vector_store.py` |
| Spring AI | Java 文档解析、切分、Embedding、VectorStore 写入/删除 | 事实：生产代码中 Spring AI import 仅在 `RagService` 与 VectorStore 配置 |
| FastAPI + LangChain | Python RAG API、模型流和白名单工具选择 | 事实：`ai-agent/app/main.py`、`service.py` |
| WebClient + Reactor | Java 消费 Python SSE 与取消上游 | 事实：`PythonPublicRagClient.java`、`SseStreamLifecycle.java` |
| Aliyun OSS | 文档源文件上传、读取、清理 | 事实：`AliOssService.java` |
| Vue 2 / Element UI | 聊天和后台页面 | 事实：`ruoyi-ui` |

不要再把 Java 聊天描述为 Spring AI `ChatClient` 或 `QuestionAnswerAdvisor`；这些运行时代码已移除。也不要引用已删除的 `AiController.java`、`RagController.java` 或 `EduAiFunctionConfig.java`。

## 4. 启动入口

- `事实`：Java 入口是 `ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java#main`，常用命令为 `mvn -pl ruoyi-admin -am spring-boot:run`。
- `事实`：Python 入口是 `ai-agent/app/__main__.py`，可使用 `ai-agent/start.ps1` 或 `start.sh` 启动。
- `事实`：Java 默认 `8080`，Python 默认绑定 `127.0.0.1:8090`。证据：`application.yml`、`ai-agent/app/config.py`。
- `事实`：两个服务共用 Embedding 模型、Redis URI、index 和 prefix；不一致会导致 schema/维度校验失败，而不是回退内存向量库。
- `需要本人补充`：生产部署拓扑、进程守护、TLS、反向代理和密钥管理方式。

## 5. 公开问答完整链路

```text
Vue
  -> Java Security / Redis RateLimiter
  -> ChatSessionScopeService
  -> PublicKnowledgeCacheService
  -> PythonPublicRagClient
  -> Python /rag/public/stream
  -> query Embedding + RediSearch KNN
  -> Prompt + model stream
  -> Python answer/sources SSE
  -> Java SseEmitter + RedisChatMemory + public cache
```

- `事实`：来源从真实检索 chunk 的 metadata 转换，不由模型生成。证据：`ai-agent/app/main.py`、`RetrievedChunk#to_source`。
- `事实`：Python 不可用时 Java 发送明确错误并结束 SSE，没有旧 Java 模型路径。证据：`SseChatController#handlePublicPythonFailure`。
- `事实`：缓存只用于无历史消息的公共首轮回答，Key 包含知识库版本与问题摘要。证据：`SseChatController#startPublicPythonStream`、`PublicKnowledgeCacheService`。
- `推断`：版本化 Key 避免文档更新时扫描删除大量缓存，但旧 Key 会等待 TTL 自然过期。

## 6. 学生工具完整链路

```text
student JWT
  -> Java role check
  -> SecurityContext userId
  -> StudentMapper userId -> studentId
  -> direct route: StudentBusinessToolService -> MySQL
  -> other route: signed callback token -> Python allow-listed tool
  -> Java /internal/ai/tool/** -> token + conversation verification
  -> database binding re-check -> StudentBusinessToolService -> MySQL
```

- `事实`：成绩工具只接收课程名，余额工具无参数；Python schema `extra=forbid`。证据：`StudentScoreToolInput`、`CardBalanceToolInput`。
- `事实`：callback token 绑定 `userId`、`studentId`、scoped `conversationId`，有效期被收敛在 60-120 秒。证据：`AiCallbackTokenService.java`。
- `事实`：Java callback 重新查询 `userId -> Student` 并比对 token 的 `studentId`。证据：`CurrentStudentService#requireCallbackStudent`。
- `推断`：即使 Prompt 诱导模型加入另一个学生 ID，Python schema 不接收，Java 也不从 body 取身份，因此越权路径被两层截断。

## 7. 会话、SSE 与并发边界

- `事实`：会话 Key 加入频道和服务端用户 Scope，同一 `conversationId` 不会跨用户或频道共享。证据：`ChatSessionScopeService.java`。
- `事实`：Java 自有 `ChatMessage` DTO 保存 Redis `type/text`，读取最近 20 条、最多 100 条、TTL 7 天。证据：`RedisChatMemory.java`。
- `事实`：SSE 生命周期统一取消 Reactor `Disposable` 和异步 `Future`，并保证 terminal signal 幂等。证据：`SseStreamLifecycle.java`。
- `事实`：前端使用 `AbortController` 主动终止请求。证据：`ruoyi-ui/src/views/ai/chat.vue`。
- `推断`：断连处理的价值是及时释放模型连接与线程资源，不等于已经证明任意并发量下稳定。

## 8. 评测与指标

- `事实`：`RagEvaluationRunnerTest` 调 Python `/rag/retrieve` Top-5，计算 HitRate@1/@3/@5；空正式数据直接失败。证据：评测器与 `evaluation/data/ground-truth.json`。
- `事实`：性能脚本以客户端收到首个 SSE `answer` 事件作为首 Token，以流结束作为完整响应。证据：`evaluation/performance-benchmark.mjs`。
- `事实`：Python 暴露 `campus.ai.rag.retrieval`、`campus.ai.llm.stream`、`campus.ai.chat.first-token`；Java 保留 `campus.ai.chat.complete` 和 Redis cache hit/miss。证据：`ai-agent/app/metrics.py`、`SseChatController.java`、`PublicKnowledgeCacheService.java`。
- `需要本人补充`：正式 Ground Truth、固定 workload、真实运行环境和由此得到的任何命中率/延迟/缓存命中率数字。

## 9. 当前可证明与不可证明

| 说法 | 结论 | 依据或缺口 |
|---|---|---|
| Java + Python 双服务 RAG 已实现 | 可以写 | 两端代码和 SSE 协议存在 |
| 个人工具不信任模型身份参数 | 可以写 | schema、token、数据库复核 |
| 会话隔离和 SSE 取消已编码并有测试 | 可以写 | Scope/Memory/Lifecycle 测试 |
| Top-3 命中率达到某个百分比 | 不能写 | 正式 Ground Truth 为空 |
| 平均响应或 P95 达到某个值 | 不能写 | 没有本次真实集成报告 |
| 缓存命中率达到某个值 | 不能写 | 计数器存在，但没有固定 workload 结果 |
| 已接入某个文档数量 | 不能写 | 没有 OSS/DB 盘点证据 |
| 安全回归在真实环境全部通过 | 只有实际脚本通过后才能写 | 需要两个真实账号和完整依赖 |

## 10. 面试最可能追问

- 为什么 Java 保留身份和 MySQL，Python 只做 Agent？
- 为什么 callback token 同时绑定用户、学生和 scoped conversation？
- 为什么成绩/余额有确定性直达路径，其他问题才交给模型选工具？
- Java 导入和 Python 检索如何保证 Redis schema、Embedding 维度与分数口径一致？
- 缓存为什么只覆盖公共首轮，知识库更新如何失效？
- Python first-token 与浏览器首 `answer` 事件为什么是两个不同测量边界？
- SSE 断开后 Java 和 Python 上游如何停止？
- Ground Truth 为空时为什么必须失败，而不是报告 0%？

## 11. 建议阅读顺序

1. `README.md` 和 `docs/architecture.md`
2. `SseChatController.java`、`PythonPublicRagClient.java`
3. `ai-agent/app/main.py`、`service.py`、`vector_store.py`
4. `AiCallbackTokenService.java`、`AiToolCallbackController.java`、`CurrentStudentService.java`
5. `StudentBusinessToolService.java`、`RedisChatMemory.java`、`SseStreamLifecycle.java`
6. `RagService.java`、`SpringAiRedisVectorStoreConfig.java`
7. `evaluation/README.md` 和两个 Node 脚本

`需要本人补充`：面试时把本人做过的取舍、事故、调试过程和实际结果补进来；不要把仓库中没有的数字或生产经历说成事实。
