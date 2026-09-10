# 校园智能知识库问答平台实施报告

报告初始基线：2026-08-12；第四步收尾更新：2026-08-16。已执行与迁移相关的 Java/Python 单元测试、编译、脚本语法检查和空数据集负向评测；因本机缺少真实基础设施、凭据和测试账号，未执行真实集成、安全回归或性能压测。

## 1. 修改文件

配置与文档：`.env.example`、`.gitignore`、`README.md`、`application.yml`、`application-druid.yml`、`sql/patch_knowledge_document_identity.sql`、`evaluation/**`。

后端主链路：`SseChatController`、`PythonPublicRagClient`、`AiToolCallbackController`、`AiCallbackTokenService`、`AiCallbackTokenAuthenticationFilter`、`ChatSessionScopeService`、`RedisChatMemory`、`SseStreamLifecycle`、`PublicKnowledgeCacheService`、`AiMetricsController`、`ChatIntentRouter`、`CurrentStudentService`、`StudentBusinessToolService`、`RagService`、`RagProperties`、`KnowledgeBaseVersionService`、`KnowledgeDocServiceImpl`、`SpringAiRedisVectorStoreConfig`、`KnowledgeDocController`、`RateLimiterAspect`、`ThreadPoolConfig` 及知识文档 Domain/Mapper/XML/API/UI。

测试源码：权限/Tool、Router、会话 Scope、Redis Memory、公开缓存、限流、RAG 来源与删除、文档去重/重建、SSE 生命周期、RAG 评测器共 11 个测试类；另有 2 个 Node 端到端脚本。

删除旧链路：`AiController`、`RagController`、`AiKnowledgeService` 和无断言的 Redis 连接空壳测试。

## 2. 安全修复

- 所有 API Key、JWT Secret、MySQL/Redis/OSS/Druid 凭据改为环境变量；`.env` 被忽略，只提交空示例。
- 学生聊天入口取消匿名放行，必须登录且具有 `student` 角色。
- Tool Schema 不暴露 `studentId/userId`；成绩仅接收 `subject`，余额工具零参数。
- Tool 执行时从 `SecurityContext` 重新解析 `userId -> Student -> studentId`；callback 再校验 token 身份与数据库绑定。
- 课程名使用固定别名白名单；Mapper 使用 MyBatis 参数绑定。
- 会话 Key 加入频道与服务端用户 Scope，阻断 conversationId 横向复用。
- Redis Lua 限流真实执行；字符串序列化保证 Lua 数字参数有效，Redis 异常时拒绝请求而非无限放行。
- 日志不记录完整 Prompt、callback token、studentId、成绩或余额。
- Redis 不支持 RediSearch 时启动失败，不再静默退化到重启即丢失的内存向量库。

已从当前配置文件移除明文值不代表 Git 历史中的旧凭据自动失效。需要人工到对应平台轮换旧凭据，并根据实际泄露面决定是否清理 Git 历史。

## 3. 用户问题完整调用链

```text
Vue fetch + conversationId + JWT（如已登录）
  -> Spring Security / @Anonymous 公共端点
  -> Redis Lua 限流
  -> ChatSessionScopeService 生成服务端 scoped conversation ID
  -> ChatIntentRouter
  -> PUBLIC_KNOWLEDGE: 公开缓存 / Python RAG 代理 / SSE
  -> STUDENT_SCORE 或 CARD_BALANCE: Java 规则直达 / MySQL / SSE
  -> 其他学生问题: callback token -> Python RAG + 受控 Tool -> Java callback / SSE
```

学生频道中不属于规则命中的问题转发到 Python RAG。Python 仅通过 LangChain 白名单工具选择成绩/余额操作，工具使用 Java 签发的短时 callback token 回调 Java；Python 不连接 MySQL。公开频道永远不会路由到个人 Tool。

## 4. 公开知识 RAG 链路（第二步后）

```text
问题 -> Java 首轮公开缓存查询
-> 未命中时 WebClient -> Python /rag/public/stream
-> Python 查询 Embedding + RediSearch KNN / Prompt / 模型流
-> Java 透传 answer/sources -> Java 写回记忆与可选公开缓存
```

模型被要求只依据检索片段回答；无足够片段时明确说明信息不足。来源数据直接取自实际检索结果，不由模型生成。

## 5. 个人业务 Tool 链路

```text
学生问题 -> 角色校验 -> Router
-> 命中成绩/余额规则: Java 直接执行（不调用 LLM）
-> 其他问题: Java 签发绑定 userId/studentId/scoped conversationId 的短时 callback token -> Python
-> Python LangChain 白名单工具（成绩仅 subject、余额零参数）回调 Java 内部接口
-> Java 校验 token、conversationId 和 CurrentStudentService 数据库绑定
-> StudentScoreMapper / CampusCardMapper -> 结构化结果 -> SSE answer
```

规则命中的成绩/余额走直达 Tool，不调用 LLM；学生频道其他问题仍允许模型在白名单函数范围内调用 Tool。

## 6. userId + conversationId 隔离

Redis 最终 Key 为：

```text
ai:chat:memory:{public|student}:{user:{userId}|anonymous:{serverSessionId}}:{conversationId}
```

Python 不能访问 MySQL。Java 内部 callback 接口位于 `/internal/ai/tool/**`，仅接受独立签名 callback token，不标记 `@Anonymous`；token 默认有效期限制在 60-120 秒，且绑定 userId、studentId 和 scoped conversationId。

`conversationId` 只接受 1-128 位字母、数字、点、下划线、冒号和连字符。所有读取、写入、clear 都先走同一个 Scope 服务。公共/学生频道、用户 A/B、匿名浏览器 A/B、同一用户不同 conversationId 均形成不同 Key。

## 7. RAG Chunk

先按标题、章节、条款和自然段进行确定性粗切分；超长段落再交给 `TokenTextSplitter`。Overlap 只发生在同一章节相邻 Chunk 之间，不跨章节拼接。默认参数为 chunk 800、最小字符 200、overlap 100，可通过环境变量调整，但必须使用同一真实评测集比较后才能决定参数。

校园规章制度的标题和条款本身携带语义边界，完全按固定字符数切分容易把条件、例外或同一条款拆开，因此先保留结构边界，再对超长文本做长度约束。

## 8. Metadata

每个向量 Chunk 保存：`docId`、`fileName`、`sourceUrl/source`、`documentType`、`section`、`chunkIndex`、`updatedAt`、`contentHash`。Chunk ID 由 `docId + contentHash + chunkIndex` 稳定生成。无法可靠解析页码时不生成页码字段。

## 9. 文档去重和更新

- 上传前计算 SHA-256，DB 唯一索引和服务层查询共同拒绝重复内容。
- 新增只允许文件导入，不再支持手填 OSS URL 只写 DB。
- 更新必须上传替换文件：保存旧快照，更新 DB，按 docId 删除旧向量，再解析/切片/Embedding/入库。
- 核心失败会尽力恢复旧 DB/向量并清理新 OSS；核心成功后再清理旧 OSS，清理失败只记录可重试告警。
- 删除先移除向量和 DB，使文档不可检索/不可查询，再清理 OSS；DB 删除失败会尝试恢复向量。

OSS、MySQL 和 Redis VectorStore 不支持跨系统 ACID，本实现采用顺序写入和补偿，仍需生产任务/告警处理极端补偿失败。

## 10. 来源返回前端

后端在回答结束后发送 JSON SSE `sources` 事件，包含 `docId/fileName/section/chunkIndex/sourceUrl/score`。Vue 将来源展示在对应助手消息下方，可点击打开 OSS 原文。没有实现或宣称精确 PDF 页码跳转。

## 11. Ground Truth 格式

```json
{
  "datasetVersion": "human-v1",
  "samples": [
    {
      "id": "q001",
      "question": "真实问题",
      "expectedSources": [
        { "docId": "真实 docId", "section": "可选真实章节" }
      ]
    }
  ]
}
```

一个问题支持多个正确来源。`section` 为空时只比较 docId。`_example` ID 和空数据集会被评测器拒绝。

## 12. HitRate@3

每个问题用真实 Retriever 取 Top-5。对 K=1/3/5，实际前 K 个来源中任一来源与任一 Ground Truth 的 docId 相同，且标注了 section 时章节也相同，即为命中。`HitRate@K = 命中问题数 / 总问题数`。报告保存总数、命中/失败数、各 K 命中率，以及每条问题的 Ground Truth 和实际 Top-5。

## 13. 缓存

只对公共频道、没有历史消息的首轮 `PUBLIC_KNOWLEDGE` 查询缓存。Key 为 `ai:public-answer:v{knowledgeBaseVersion}:{SHA-256(normalizedQuestion)}`，TTL 2 小时。Miss 后由 Python 代理执行 RAG + LLM，Java 在成功完成后缓存答案与真实来源。知识库成功导入、更新或删除会递增版本，无需扫描删除即可使旧缓存不可命中。

## 14. 缓存隐私

学生频道完全不调用 `PublicKnowledgeCacheService`；成绩、余额和多轮上下文不进入共享缓存。安全回归脚本会读取公开缓存 hit/miss，在学生请求前后断言计数不变。当前没有执行该脚本，因此不能声称此集成验证已通过。

## 15. SSE 中断

60 秒 timeout、completion、error 和发送 IOException 都统一进入 `SseStreamLifecycle`。`close` 原子且幂等，会 dispose Reactor 订阅并 cancel 异步 Future；终止信号也只发送一次。Vue 用 `AbortController` 支持停止、切换路由、组件销毁和新请求覆盖旧请求，并在中断时取消 Reader。

## 16. 验证状态

第四步的最新验证结果见第 20 节。仍未生成 RAG 命中率、缓存命中率、首 Token/完整响应延迟、Tool 成功率或真实安全回归结论。

## 17. 当前限制

- 真实 500 条人工 Ground Truth 未在仓库中，正式数据文件为空。
- 未对 200+ 文档数量做 OSS/DB 盘点，不能引用该数字。
- 外部系统之间使用补偿而非分布式事务；补偿连续失败仍需人工处理。
- 只实现基础向量检索，没有 reranker/hybrid search；当前阶段不应为简历堆叠这些技术。
- 匿名会话依赖同源 Cookie/HttpSession；跨站部署必须正确配置 Cookie/CORS。
- `sourceUrl` 是否可公开访问取决于 OSS Bucket/签名策略，当前不是细粒度文档 ACL 系统。
- 现有 Spring AI 版本为 milestone，仅文档导入/VectorStore 管线仍依赖其 API，后续升级需验证索引 schema 和切分行为。

## 18. 简历对照表

| 描述 | 结论 | 依据或缺口 |
|---|---|---|
| A. 将问题分类为公开知识与个人业务并设计不同流程 | 可以写 | Router 与两条主链路已实现 |
| B. 平均响应从约 1.2s 降到 800ms | 不能写 | 压测脚本存在，但没有真实前后对照数据 |
| C. 缓存命中率约 60% | 不能写 | 有真实计数器，无固定 workload 运行结果 |
| D. 个人业务成功率稳定 95%+ | 不能写 | 无真实集成样本与结果 |
| E. 接入 200+ 校园制度文档到 OSS | 不能写 | 未盘点真实 OSS/DB 数量，未造假文档 |
| F. 500 条样本 Top-3 命中率 95% | 不能写 | 评测器已实现，真实 500 条数据未导入/运行 |
| G. 答案与原文段落可视化关联 | 可以写 | 后端 sources + Vue 来源展示已实现 |
| H. Function Calling 白名单与参数校验 | 可以写 | Python 两个工具 Schema 无身份字段，Java callback token 与数据库身份二次校验 |
| I. 压测未出现明显越权 | 不能写 | 安全脚本已写但未运行 |
| J. SSE + Redis 支持多轮对话中断与清理 | 可以写 | 生命周期、Scoped Redis Memory、clear API 与前端 Abort 已实现 |
| K. 关键词快速通道与 RAG 兜底双路径 | 可以写，但应改述 | 准确表述为“确定性业务规则分流 + 公开知识 RAG”，不是 ML 分类器 |
| L. 降低模型调用成本 | 谨慎写 | 只能写“部分成绩/余额查询无需调用大模型”；没有 Token/费用统计，不能量化 |

## 19. 第二步公开流式入口迁移

公开入口仍按原有顺序执行限流、`ChatIntentRouter` 和公开答案缓存。缓存未命中后，Java 通过环境变量 `PYTHON_AGENT_BASE_URL` 配置的 WebClient 调用 `ai-agent` 的 `/rag/public/stream`，透传 Python 的 `answer`/`sources` JSON SSE 事件；Python 服务不可用时发送明确的 `error` 事件，不静默回退到 Java 模型链路。

Java 继续生成 scoped conversation ID、读取最近 20 条 `RedisChatMemory` 消息并传给 Python，完成后再写回用户问题和助手答案。缓存 key、知识库版本失效、hit/miss 计数器和学生频道均保持原语义。SSE 仍使用 60 秒超时及 `SseStreamLifecycle` 的客户端断连、`Disposable`/`Future.cancel(true)` 取消机制。

公开代理不再在 Java 侧记录 `campus.ai.rag.retrieval`、`campus.ai.chat.first-token` 或 `campus.ai.llm.stream`；这些指标由 Python 侧负责。端到端 `campus.ai.chat.complete` 仍由 Java 记录。

本步已通过 Python 单测、Java Python SSE 协议测试、控制器缓存/记忆测试、SSE 生命周期测试和 `mvn -DskipTests compile`。MySQL、Redis/RediSearch、模型服务和 Python/Java 联调需在真实环境运行后，才能验证二次缓存命中不调用 Python、断连取消及完整 SSE 事件；当前不产出命中率或性能结论。

## 20. 第四步评测、指标与依赖收尾

- `RagEvaluationRunnerTest` 已改为调用 Python `/rag/retrieve` 获取 Top-5。空正式数据集会在网络调用和结果写入前失败；本次负向运行确认未生成结果文件。
- Python `/metrics` 暴露 `campus.ai.rag.retrieval`、`campus.ai.llm.stream`、`campus.ai.chat.first-token` 的 count/total/average/max；无样本不推导平均值。
- Java 保留 `campus.ai.chat.complete`、`campus.ai.llm.call`、`campus.ai.tool.query` 和 Redis cache hit/miss。具体边界见 `docs/python-agent-migration.md`。
- Java 聊天运行时代码已移除 Spring AI `ChatClient`、Function Calling、`ToolContext` 和 `ChatMemory`；Spring AI 生产引用只剩文档解析、切分、Embedding/VectorStore 写入和按 metadata 删除。
- `security-regression.mjs` 覆盖 callback token、账号隔离、成绩、余额、缓存 miss/hit、Python 指标、限流和 SSE 中断；`performance-benchmark.mjs` 以首个 SSE `answer` 事件作为首 Token。
- 本次离线验证：`mvn -DskipTests compile`、`mvn -DskipTests test-compile` 成功；Java 37 项测试通过、命中率评测默认跳过；Python 13 项测试通过；两份 Node 脚本通过 `node --check`。
- 本次真实集成未运行：本机无 `.env`，相关凭据和两个学生/管理员测试 token 未配置，且 MySQL、Redis Stack、Java、Python 端口均未监听。因此没有生成命中率、延迟、缓存命中率或安全回归通过结论。
