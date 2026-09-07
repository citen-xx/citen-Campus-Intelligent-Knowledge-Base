# 项目面试题库

说明：
- 仓库中未找到 `interview-prep/resume.md` 和 `interview-prep/job.md`，本题库主要依据 `interview-prep/project-brief.md`、代码、`docs/`、`benchmark/` 和 git 历史生成。
- 第 `L` 节按本项目最可能对应的 Java / Spring Boot / AI 后端岗位设计，具体 JD 需要你本人补充后再微调。

## A. 项目介绍题

#### A1. 这个项目到底解决了什么问题？为什么要拆成公共问答、学生工具和管理员查询三条链路？
- 面试官问题：这个系统不是单纯的 AI Chat，而是怎么把校园制度问答和学生个人数据查询放进同一个后端又不混用的？
- 提问意图：看你是否真的理解业务边界、权限边界和系统边界。
- 优秀回答应包含的关键点：校园制度咨询适合走 RAG，学生成绩/一卡通适合走受控工具调用，管理员全量查询必须走独立后端接口；三条链路的核心目标是避免把公共知识和敏感个人数据混在同一条提示词或同一套工具里。
- 项目中的代码证据：`docs/interview.md#2.1`、`docs/interview-expanded.md#2.3`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AdminEduController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/KnowledgeDocController.java`
- 可能的连续追问：为什么不能只做一个统一接口加参数？为什么管理员不能复用学生工具链？怎么防 prompt 注入越权？
- 常见低质量回答：就是做了一个校园 AI 问答平台。
- 难度：中等

#### A2. 你会怎么用 1 分钟介绍这个项目？
- 面试官问题：如果面试官只给你 60 秒，你怎么讲得清楚又不夸大？
- 提问意图：看你能不能抽象出项目主线，而不是堆技术名词。
- 优秀回答应包含的关键点：一句话定位项目，二到三个核心能力，最后补一句自己的实际贡献范围；可以强调 `RuoYi + Spring Boot + Spring AI + SSE + Redis + OSS`，但要把“公共问答”“学生工具调用”“管理员查询”“知识库管理”这几条主线讲清楚。
- 项目中的代码证据：`docs/interview.md#2.2`、`docs/mock-interview-final.md#1`、`README.md`
- 可能的连续追问：你自己的贡献是什么？如果删掉一个功能，你最不舍得删哪条链路，为什么？
- 常见低质量回答：用了很多技术，做起来很完整。
- 难度：基础

#### A3. 用户或调用方如何使用它？
- 面试官问题：不同角色具体怎么进入系统、怎么调用接口、怎么得到结果？
- 提问意图：看你是否真的知道 API 入口、认证方式和使用路径。
- 优秀回答应包含的关键点：先登录拿 token，再按角色使用不同入口；匿名用户走公共问答，`student` 角色走学生聊天或自助成绩/一卡通接口，`admin` 角色走独立管理接口，知识库管理员走文档上传与导入接口。
- 项目中的代码证据：`README.md`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysLoginController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/KnowledgeDocController.java`
- 可能的连续追问：未绑定学生时怎么处理？匿名访问公共问答为什么可以？前端页面对应哪个入口？
- 常见低质量回答：前端调后端接口就能用了。
- 难度：基础

## B. 技术实现题

#### B1. `AiController#chat` 的老链路是怎么工作的？
- 面试官问题：从 `POST /system/ai/chat` 进入后，代码里到底发生了什么？
- 提问意图：看你是否真正理解老版 RAG 链路的缓存、限流、检索和流式返回。
- 优秀回答应包含的关键点：先做 Redis 缓存命中，再执行滑动窗口限流，随后通过 `QuestionAnswerAdvisor` 把 `VectorStore` 检索结果注入提示词，最后用 `ChatClient.stream()` 输出结果；同时要说明这条链路是迁移前的 legacy 实现。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java#chat`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java#init`、`git commit cb4f59bb`
- 可能的连续追问：为什么缓存 key 按 query 而不是 conversationId？为什么这条接口还留着？限流脚本为什么写在 controller 里？
- 常见低质量回答：就是直接调大模型接口。
- 难度：中等

#### B2. `RagService#importInputStreamToVectorStore` 如何把文档变成向量？
- 面试官问题：文档从 OSS 进来后，代码如何解析、切块、补 metadata 并写入向量库？
- 提问意图：看你是否理解 RAG 的输入处理，而不是只会说“先上传再检索”。
- 优秀回答应包含的关键点：先从 OSS 拿输入流，再用 `TikaDocumentReader` 解析文本，用 `TokenTextSplitter` 切块，补充 overlap 和文档来源 metadata，最后写入 `VectorStore`；还要知道空文档、解析失败和异常时如何处理。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#importInputStreamToVectorStore`、`ruoyi-system/src/main/java/com/ruoyi/system/Oss/AliOssService.java#getObjectInputStreamByUrl`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/KnowledgeDocController.java#importFile`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/RagController.java#uploadAndImport`
- 可能的连续追问：为什么 chunk size 是 800？为什么 overlap 是 120？如果 PDF 解析为空怎么办？
- 常见低质量回答：上传完文件直接塞到向量库里了。
- 难度：中等

#### B3. 为什么 `retrieveRelevantDocuments` 只取 `TOP_K = 3`、阈值用 `0.6d`？
- 面试官问题：这两个参数为什么这么设，调大调小会发生什么？
- 提问意图：看你是否能讲出召回率、精确率、上下文长度和成本之间的权衡。
- 优秀回答应包含的关键点：`TOP_K` 太大容易引入噪声并挤占上下文，太小会漏召回；相似度阈值越高越保守，越低越容易召回无关片段；如果没有真实 benchmark，回答里要明确这是经验参数而不是性能结论。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#retrieveRelevantDocuments`、`docs/benchmark.md`、`docs/interview.md`
- 可能的连续追问：你怎么评估检索质量？如果要做离线评测，样本和指标怎么设计？
- 常见低质量回答：我感觉 3 比较合适。
- 难度：困难

#### B4. `EduAiFunctionConfig` 和 `CurrentStudentService` 如何保证工具只查当前学生？
- 面试官问题：为什么模型不能自己决定 `studentId`？
- 提问意图：看你是否理解 Function Calling 的权限收口和身份绑定。
- 优秀回答应包含的关键点：工具函数描述里明确禁止传入或切换 `studentId`，真正的学生身份从 `SecurityUtils.getUserId()` 和 `ToolContext` 里解析；`CurrentStudentService` 通过 `StudentMapper#selectStudentByUserId` 找到登录用户绑定的学生记录，学生只能查自己。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java#getStudentScore`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java#getCardBalance`、`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java#requireCurrentStudentId`、`ruoyi-system/src/main/resources/mapper/system/StudentMapper.xml#selectStudentByUserId`、`sql/patch_student_user_binding.sql`、`docs/security-test.md`
- 可能的连续追问：如果模型提示用户传另一个 `studentId` 怎么办？如果学生账号没绑定数据库记录怎么处理？admin 为什么不用这套工具？
- 常见低质量回答：前端不传 `studentId` 就安全了。
- 难度：困难

#### B5. `RedisChatMemory` 和 `SpringAiRedisVectorStoreConfig` 分别解决什么问题？
- 面试官问题：为什么要把会话记忆和向量检索都放到 Redis 相关组件里？
- 提问意图：看你是否能讲清楚多轮对话、重启恢复和向量检索的差异。
- 优秀回答应包含的关键点：`RedisChatMemory` 负责会话上下文持久化、TTL 和历史截断，解决多轮对话断开后上下文丢失问题；`SpringAiRedisVectorStoreConfig` 优先使用 Redis Stack / RediSearch，环境不支持时回退到 `SimpleVectorStore`，保证开发部署可用但要说明能力差异。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#chatMemoryAdvisor`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/SpringAiRedisVectorStoreConfig.java`、`docs/benchmark.md`
- 可能的连续追问：为什么 TTL 设成 7 天？SimpleVectorStore 回退后有什么缺点？Redis 没有 RediSearch 会不会影响生产？
- 常见低质量回答：就是把聊天记录缓存到 Redis。
- 难度：中等

## C. 架构和数据流题

#### C1. `POST /api/ai/chat/public/stream` 的完整链路是什么？
- 面试官问题：从前端发起公共问答到后端流式吐字，经过了哪些对象和方法？
- 提问意图：看你是否能把控制器、检索、模型和 SSE 串成一条完整链路。
- 优秀回答应包含的关键点：请求进入 `SseChatController#publicStreamChat` 后，先做缓存和限流判断，再走 `streamChatInternal`，其中先检索相关文档，再构建提示词，调用 `ChatClient` 生成流式响应，最后通过 `SseEmitter` 回给前端；如果命中缓存，可以绕过模型。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#publicStreamChat`、`#streamChatInternal`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#retrieveRelevantDocuments`、`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`、`ruoyi-ui/src/views/ai/chat.vue`
- 可能的连续追问：缓存和 RAG 的先后顺序为什么这样排？流式过程中某个 chunk 失败怎么办？前端怎么取消？
- 常见低质量回答：就是把模型输出一段一段返回。
- 难度：中等

#### C2. `POST /api/ai/chat/student/stream` 的完整链路是什么？
- 面试官问题：学生聊天入口如何保证只查当前登录学生的数据？
- 提问意图：看你是否真正理解身份解析、工具分发和 SSE 的组合。
- 优秀回答应包含的关键点：先校验学生身份，再构造 `ToolContext`，通过 `getStudentScore` / `getCardBalance` 这类受控工具返回结果；如果是明确的单工具意图，还会走直接工具分发路径；整个过程不能依赖用户输入的 `studentId`。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#studentStreamChat`、`#ensureStudentAccess`、`#handleDirectStudentToolCall`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java`
- 可能的连续追问：为什么要做单工具强制分发？如果模型想查别人的成绩怎么办？工具调用失败时你怎么返回？
- 常见低质量回答：模型自己会识别学生身份。
- 难度：困难

#### C3. 知识文档从上传到检索再到删除的链路怎么走？
- 面试官问题：一份校园制度文档是怎样进入系统、被检索、再被删除的？
- 提问意图：看你是否理解知识库的生命周期，而不是只知道“上传后可问答”。
- 优秀回答应包含的关键点：文档先上传到 OSS，再落 `knowledge_doc` 记录，然后异步导入向量库；检索时根据 metadata 找到相关 chunk；删除时先删向量，再删 OSS，最后删 DB，并说明失败时的 fallback 和日志定位。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/KnowledgeDocController.java#importFile`、`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/KnowledgeDocServiceImpl.java#asyncUploadToDifyEngine`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#importOssFileToVectorStore`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#deleteByFileUrl`、`ruoyi-system/src/main/java/com/ruoyi/system/Oss/AliOssService.java`、`docs/knowledge-delete-test.md`
- 可能的连续追问：为什么删除顺序不能反过来？同名文件怎么避免误删？删库失败怎么办？
- 常见低质量回答：上传和删除都调一下接口就行。
- 难度：困难

#### C4. SSE 断连、超时、异常时后端如何释放资源？
- 面试官问题：客户端中途断开连接，后端还在生成答案时怎么处理？
- 提问意图：看你是否能讲出资源释放、取消和幂等清理。
- 优秀回答应包含的关键点：`StreamLifecycle` 同时持有 `Disposable` 和 `CompletableFuture`，在超时、完成、异常时统一关闭；`safeSend` 处理客户端断开；公共流和学生流的取消方式不同，但都要避免后端继续占用模型和线程资源。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#StreamLifecycle`、`#safeSend`、`benchmark/security/sse-abort-test.js`、`docs/benchmark.md`
- 可能的连续追问：怎么区分客户端主动关闭和服务端故障？取消失败时怎么办？如何验证真的释放了资源？
- 常见低质量回答：浏览器关掉了就自然结束了。
- 难度：困难

## D. 技术选型与取舍题

#### D1. 为什么从 Dify 迁到 Spring AI？`cb4f59bb` 解决了什么？
- 面试官问题：为什么不继续用 Dify，而是重构成 Spring AI？
- 提问意图：看你是否能讲清楚“换技术”的业务原因，而不是只会讲喜好。
- 优秀回答应包含的关键点：迁移的核心不是“Spring AI 更新”，而是把 prompt、tool、memory、streaming 的控制权收回到 Java 后端里，减少外部平台耦合，方便做权限边界、日志和生命周期管理。
- 项目中的代码证据：`git commit cb4f59bb`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/AiKnowledgeService.java`、`pom.xml`、`ruoyi-system/pom.xml`
- 可能的连续追问：迁移过程中丢掉了什么能力？保留了什么？为什么没有完全删掉旧链路？
- 常见低质量回答：Spring AI 比 Dify 好用。
- 难度：中等

#### D2. 为什么必须拆成 public/student/admin 三条链路，而不是一个接口加参数？
- 面试官问题：如果统一成一个 AI 接口加一个 `type` 参数，不是更省事吗？
- 提问意图：看你是否理解高风险数据的隔离设计。
- 优秀回答应包含的关键点：公共问答、学生个人数据、管理员全量查询的权限模型完全不同；拆链路后，入口、工具集、日志和鉴权逻辑都更清晰，也更容易单独测试和审计。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AdminEduController.java`、`docs/security-test.md`、`docs/interview-expanded.md`
- 可能的连续追问：统一接口真的不能做吗？为什么不在 service 层做策略分发？有没有额外增加前端复杂度？
- 常见低质量回答：分开写更直观。
- 难度：中等

#### D3. 为什么用 Redis VectorStore，还要保留 `SimpleVectorStore` 回退？
- 面试官问题：如果 Redis 不支持 RediSearch，为什么不直接报错？
- 提问意图：看你是否理解可部署性和能力边界的取舍。
- 优秀回答应包含的关键点：Redis Stack / RediSearch 提供真正的向量检索能力，回退到 `SimpleVectorStore` 主要是为了开发或非完整环境可运行，但必须说明回退后能力会下降，不能把它当成生产等价方案。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/config/SpringAiRedisVectorStoreConfig.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java`、`docs/benchmark.md`
- 可能的连续追问：两种实现的能力差异是什么？怎么避免在生产环境误落回退实现？
- 常见低质量回答：为了兼容不同环境。
- 难度：中等

#### D4. 为什么没有把 RAG、工具调用和会话记忆收敛成一个统一 agent？
- 面试官问题：既然都是 AI 能力，为什么现在还是拆开实现？
- 提问意图：看你是否能在“统一”和“可控”之间做取舍。
- 优秀回答应包含的关键点：当前项目更偏工程化 pipeline，而不是全自动 agent；公共问答需要可追溯，学生工具需要强约束，记忆需要可控失效，拆开后更容易控制边界，也更容易定位问题。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`
- 可能的连续追问：如果以后升级成 agent，你会把哪些逻辑收进去，哪些保留在后端策略层？
- 常见低质量回答：agent 比较先进。
- 难度：困难

## E. 性能和并发题

#### E1. 为什么这里用 SSE，而不是一次性 JSON、WebSocket 或轮询？
- 面试官问题：为什么你选的是 SSE？
- 提问意图：看你是否理解流式输出和交互体验之间的关系。
- 优秀回答应包含的关键点：LLM 回答是逐 token 生成的，SSE 适合单向流式下发，能更早给前端首包并模拟“正在输出”的体验；比 WebSocket 更简单，也更贴合这条链路的单向特征。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java`、`ruoyi-ui/src/views/ai/chat.vue`、`docs/benchmark.md`
- 可能的连续追问：如果以后要双向交互怎么办？SSE 在代理层和超时上有什么风险？
- 常见低质量回答：SSE 比较酷。
- 难度：中等

#### E2. 断开连接时，后端如何保证不继续耗资源？
- 面试官问题：前端点了停止或切页后，服务端会发生什么？
- 提问意图：看你是否真的做了 cancellation，而不是只做了页面上的停止按钮。
- 优秀回答应包含的关键点：前端通过 `AbortController` 停止请求，后端通过 `StreamLifecycle` 统一取消 `Flux Disposable` 或 `Future`，并且需要说明这一点能避免 LLM 继续生成和线程继续占用。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#StreamLifecycle`、`benchmark/security/sse-abort-test.js`、`docs/benchmark.md`
- 可能的连续追问：如何区分“用户中断”与“服务异常”？如果取消失败会不会泄漏线程？
- 常见低质量回答：前端关了就不会再发了。
- 难度：困难

#### E3. `RateLimiterAspect` 既然存在，为什么又在 `18eacb88` 里暂时关闭？
- 面试官问题：项目里的限流到底有没有真正生效？
- 提问意图：看你是否能诚实说明现状，而不是把注释代码说成已上线能力。
- 优秀回答应包含的关键点：全局 `RateLimiterAspect#doBefore()` 目前是空的，实际限流更多体现在 controller 里的 Redis Lua 脚本；`18eacb88` 说明这是一次临时调整，不应把“全局限流已完善”当成事实。
- 项目中的代码证据：`git commit 18eacb88`、`ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/RateLimiterAspect.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java#init`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#init`
- 可能的连续追问：最终你会把限流放在哪里？annotation 级、网关级还是 controller 级？
- 常见低质量回答：已经做了限流。
- 难度：困难

#### E4. 如果面试官追问“你说响应时间降了、成功率 95%+、QPS 更高”，你能拿什么证据回答？
- 面试官问题：这些数字在仓库里有没有真实压测或日志支撑？
- 提问意图：看你是否知道哪些结论能证实，哪些只能说“需要本人补充”。
- 优秀回答应包含的关键点：当前仓库里有 benchmark 脚本和验证说明，但没有完整、可引用的真实压测报告；可以诚实说明只验证了 abort / 安全脚本和一些运行行为，不能把没落盘的数字当事实。
- 项目中的代码证据：`docs/benchmark.md`、`benchmark/README.md`、`benchmark/security/sse-abort-test.js`、`docs/interview.md`
- 可能的连续追问：如果要补齐这些证据，你会采集哪些指标？怎么做离线评测和线上监控？
- 常见低质量回答：体感上快了很多。
- 难度：中等

## F. 数据库与缓存题

#### F1. `student.user_id -> sys_user.user_id` 为什么是这条关键关系？
- 面试官问题：学生身份为什么不是前端传一个 `studentId` 就行？
- 提问意图：看你是否理解身份绑定是从哪里来的。
- 优秀回答应包含的关键点：真正的学生身份来源于登录账号与学生记录的绑定，`CurrentStudentService` 通过 `userId` 找到 `student` 表记录；这样工具调用和自助接口才能稳定地从后端复原身份，而不是依赖前端或模型输入。
- 项目中的代码证据：`sql/patch_student_user_binding.sql`、`ruoyi-system/src/main/java/com/ruoyi/system/domain/Student.java`、`ruoyi-system/src/main/resources/mapper/system/StudentMapper.xml`、`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java`
- 可能的连续追问：如果没有绑定记录怎么办？为什么要加唯一索引？迁移老数据怎么处理？
- 常见低质量回答：这就是表关联。
- 难度：中等

#### F2. `knowledge_doc` 这张表在系统里承担什么职责？
- 面试官问题：知识库管理里，DB 记录到底存什么，和 OSS / 向量库分别什么关系？
- 提问意图：看你是否能把元数据、原文件和向量索引分开理解。
- 优秀回答应包含的关键点：`knowledge_doc` 更像文档元数据和同步状态表，负责记录文档来源、文件地址、标题、路径、处理状态等；真正的文件在 OSS，真正的语义检索在向量库。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/domain/KnowledgeDoc.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/KnowledgeDocController.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/KnowledgeDocServiceImpl.java`
- 可能的连续追问：检索结果如何回溯到原文？删除时为什么要先动向量和 OSS？
- 常见低质量回答：就是文件记录表。
- 难度：中等

#### F3. 向量库里的 metadata 为什么要加 `fileUrl`、`source`、`fileName`、`chunk`？
- 面试官问题：这些字段是为了展示吗，还是为了正确性？
- 提问意图：看你是否理解可追溯性、删除精度和同名文件问题。
- 优秀回答应包含的关键点：metadata 既用于答案溯源，也用于删除精度；`fileUrl` 能精确定位单个文件，`source/fileName` 是历史兼容兜底，`chunk` 便于定位片段；如果只靠文件名，容易发生同名误删。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#applyOverlapAndMetadata`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#deleteByFileUrl`、`docs/knowledge-delete-test.md`、`docs/interview-expanded.md`
- 可能的连续追问：旧数据没有 `fileUrl` 怎么办？如果两个文件同名你怎么处理？删除后怎么确认索引真的消失？
- 常见低质量回答：就是为了好看。
- 难度：困难

#### F4. `AiController` 和 `SseChatController` 里的 Redis key 设计有什么优点和问题？
- 面试官问题：你为什么这么设计缓存 key 和聊天记忆 key？
- 提问意图：看你是否考虑了隔离、失效和污染风险。
- 优秀回答应包含的关键点：`AiController` 用 `ai:qa:{query}` 做公共问答短路，`RedisChatMemory` 用 `conversationId` 做会话隔离；优点是简单直接，问题是 query 归一化、缓存污染、TTL 和内存增长都需要权衡。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`docs/benchmark.md`
- 可能的连续追问：为什么公共缓存不用 userId？如何避免不同人问同一句话时误解读上下文？
- 常见低质量回答：Redis key 这样写比较方便。
- 难度：困难

## G. 异常、安全和可靠性题

#### G1. 你怎么防止 prompt injection 诱导 student chat 查别人的成绩或一卡通余额？
- 面试官问题：如果用户输入“查 202301010002 的成绩”，系统会不会被带偏？
- 提问意图：看你是否理解模型输出不可直接信任。
- 优秀回答应包含的关键点：工具描述里明确禁止传 `studentId`，工具上下文由后端构造，真正的身份只从登录态和 `CurrentStudentService` 获取；即使 prompt 里出现别人的学号，也不应改变工具实际查询对象。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#resolveForcedToolName`、`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java`、`docs/security-test.md`
- 可能的连续追问：模型坚持输出别人的 `studentId` 怎么办？工具函数和 prompt 哪个更可信？
- 常见低质量回答：模型应该不会这么做。
- 难度：困难

#### G2. `CurrentStudentService` 找不到绑定学生、或者账号没登录时应该怎么表现？
- 面试官问题：身份缺失时你让系统返回什么？
- 提问意图：看你是否区分 401、403 和业务错误。
- 优秀回答应包含的关键点：找不到登录态或学生绑定时应该直接失败，而不是回退到模型猜测；公共问答和学生工具的失败语义不同，不能混用 null 或空字符串。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java#requireCurrentStudent`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#ensureStudentAccess`、`docs/security-test.md`
- 可能的连续追问：这个错误应该是 401 还是 403？为什么？
- 常见低质量回答：就返回空结果。
- 难度：中等

#### G3. `GlobalExceptionHandler` 为什么要把 SSE 断开当成特殊情况处理？
- 面试官问题：客户端取消连接算异常吗？
- 提问意图：看你是否理解流式请求里“断开”不等于“服务故障”。
- 优秀回答应包含的关键点：客户端主动断开应被视为正常生命周期事件，不能打满错误日志；服务端需要做的是幂等清理和资源释放，而不是把它当成系统错误。
- 项目中的代码证据：`ruoyi-framework/src/main/java/com/ruoyi/framework/web/exception/GlobalExceptionHandler.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#safeSend`、`docs/benchmark.md`
- 可能的连续追问：哪些异常应该记 error，哪些只记 warn？你怎么区分前端中断和后端故障？
- 常见低质量回答：捕获一下就行。
- 难度：中等

#### G4. 知识删除为什么要按“向量 -> OSS -> DB”顺序做，失败了怎么处理？
- 面试官问题：为什么不直接先删数据库记录？
- 提问意图：看你是否理解一致性和残留风险。
- 优秀回答应包含的关键点：先删向量避免检索命中残留，再删 OSS 避免外部资源泄漏，最后删 DB 保证系统主记录收尾；如果任一步失败，要通过日志和 fallback 定位，而不是静默成功。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/KnowledgeDocServiceImpl.java#deleteSingleKnowledgeDoc`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#deleteByFileUrl`、`docs/knowledge-delete-test.md`
- 可能的连续追问：如果向量删了但 OSS 没删干净怎么办？如果 DB 删除失败会不会再检索到旧文档？
- 常见低质量回答：先删哪个都行。
- 难度：困难

#### G5. `application.yml` 里有哪些明显的安全债，面试时你怎么讲？
- 面试官问题：如果面试官翻配置文件，你最怕被指出什么？
- 提问意图：看你是否敢承认问题，而不是硬说“都做得很安全”。
- 优秀回答应包含的关键点：`spring.ai.openai.api-key`、`token.secret`、Redis 密码或其他敏感配置不应该硬编码在仓库里，应迁移到环境变量或密钥管理；回答时要明确这是当前仓库存在的安全债，不要包装成已经完全解决。
- 项目中的代码证据：`ruoyi-admin/src/main/resources/application.yml`、`pom.xml`、`ruoyi-system/src/main/java/com/ruoyi/system/Oss/AliOssService.java`
- 可能的连续追问：如果密钥泄露了怎么轮换？你会怎么改部署方式？
- 常见低质量回答：这是本地配置，不重要。
- 难度：中等

## H. 测试与部署题

#### H1. 这个项目里到底有哪些真正能支撑你说法的测试或脚本？
- 面试官问题：你说做过验证，证据在哪里？
- 提问意图：看你是否区分“脚本模板”和“真实结果”。
- 优秀回答应包含的关键点：仓库里有安全测试脚本、SSE 断连脚本和压测模板，但不是每一个都带真实压测报告；能证明的主要是接口可跑、权限边界和断连清理行为。
- 项目中的代码证据：`benchmark/README.md`、`benchmark/security/*.sh`、`benchmark/security/sse-abort-test.js`、`docs/benchmark.md`、`docs/security-test.md`
- 可能的连续追问：哪些结论是脚本验证过的？哪些还需要本人补充？
- 常见低质量回答：测试很多，效果也不错。
- 难度：基础

#### H2. 如果只挑一个安全测试场景，你最愿意讲哪一个？
- 面试官问题：你会拿哪个场景来说明你真的做过权限隔离？
- 提问意图：看你是否能讲出一个完整、可复现的安全用例。
- 优秀回答应包含的关键点：最有说服力的是 student A 不能通过 prompt 或参数伪装去查 student B 的成绩或余额；同时还能说明公共问答不会触发学生工具，管理员查询走独立接口。
- 项目中的代码证据：`docs/security-test.md`、`benchmark/security/student-chat-student-a-impersonate-b.sh`、`benchmark/security/public-chat-anonymous.sh`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AdminEduController.java`
- 可能的连续追问：这个场景对应哪条日志？如果想手工复现，curl 怎么写？
- 常见低质量回答：就是做了权限控制。
- 难度：中等

#### H3. `benchmark/security/sse-abort-test.js` 这种脚本证明了什么，不能证明什么？
- 面试官问题：你怎么解读这个测试脚本的价值？
- 提问意图：看你是否知道“验证断连”不等于“性能压测”。
- 优秀回答应包含的关键点：它主要证明断连后能触发取消和清理逻辑，不能证明高 QPS、P99 或真实吞吐；如果要补齐性能结论，还需要系统性的压测和监控数据。
- 项目中的代码证据：`benchmark/security/sse-abort-test.js`、`docs/benchmark.md`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#StreamLifecycle`
- 可能的连续追问：如果把它接入 CI，你会加什么断言？
- 常见低质量回答：证明系统很稳定。
- 难度：中等

#### H4. 这个项目怎么启动，启动时你最需要检查哪些配置？
- 面试官问题：从本地到跑起来，入口和前置条件是什么？
- 提问意图：看你是否真正接触过项目的运行链路。
- 优秀回答应包含的关键点：入口是 `RuoYiApplication.main`，启动命令可以参考 README；启动前至少要确认 Redis、OSS、Spring AI 相关配置和数据库都可用，并知道哪些组件失败会直接影响 AI 链路。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java`、`README.md`、`ruoyi-admin/src/main/resources/application.yml`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/SpringAiRedisVectorStoreConfig.java`
- 可能的连续追问：哪个配置缺失最容易先报错？如何从日志快速定位是 Redis、OSS 还是模型配置问题？
- 常见低质量回答：直接 `mvn spring-boot:run` 就行。
- 难度：基础

## I. Bug、失败经历和技术债

#### I1. `da11a0d3 去除无限递归的bug` 这个 bug 你会怎么讲？
- 面试官问题：这个 commit 名字里写的“无限递归”到底是什么问题？
- 提问意图：看你是否能把 bug 讲成一个真实的排障故事，而不是只复述 commit 标题。
- 优秀回答应包含的关键点：要说清楚递归发生在什么调用链、怎么发现、怎么修复、怎么避免回归；如果你不能从代码精确证明细节，就要明确说“需要本人补充”，不要编造。
- 项目中的代码证据：`git commit da11a0d3`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysLoginController.java`、`ruoyi-common/src/main/java/com/ruoyi/common/utils/StringUtils.java`、`ruoyi-framework/src/main/java/com/ruoyi/framework/config/DruidConfig.java`
- 可能的连续追问：如果这个 bug 再出现，你会加什么测试？为什么当时会进入递归？
- 常见低质量回答：之前写错了，后来改好了。
- 难度：困难

#### I2. `RateLimiterAspect#doBefore()` 现在为什么是空的，这算不算技术债？
- 面试官问题：这个全局限流切面现在到底是“有”还是“没有”？
- 提问意图：看你是否能区分“代码存在”与“功能生效”。
- 优秀回答应包含的关键点：当前切面逻辑被注释或短路，说明全局限流并没有真正生效；这当然算技术债，因为调用方可能误以为已经被保护，而实际保护更多靠 controller 内的 Lua 脚本。
- 项目中的代码证据：`ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/RateLimiterAspect.java`、`git commit 18eacb88`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java#init`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java#init`
- 可能的连续追问：最终你会删掉它还是补回来？更适合放在网关、AOP 还是 controller？
- 常见低质量回答：只是临时关闭一下。
- 难度：中等

#### I3. `AiKnowledgeService` 和 `RagService` 为什么会出现重复能力，哪一个是真的在用？
- 面试官问题：这个项目是不是存在迁移残留或重复实现？
- 提问意图：看你是否能识别 dead code 和重构债。
- 优秀回答应包含的关键点：`AiKnowledgeService` 看起来是旧实现残留，而真正承载知识导入和检索的是 `RagService`；如果你能确认某个类几乎没被引用，就应该坦诚它是技术债而不是继续包装。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/AiKnowledgeService.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java`、`git commit cb4f59bb`、`git commit 07b4d1b9`
- 可能的连续追问：你会删掉它还是合并进去？怎样确认它真的是 dead code？
- 常见低质量回答：以后可能会用到。
- 难度：中等

## J. 个人贡献与团队协作

#### J1. 如果面试官问“这个项目里你具体做了什么”，你怎么准确说才不会夸大？
- 面试官问题：你在这项目里的职责边界到底是什么？
- 提问意图：看你是否能把个人贡献和团队结果拆开说。
- 优秀回答应包含的关键点：用“参与、负责、实现、验证”描述自己真正做的部分，明确区分主线功能、验证脚本和团队协作产物；不要把整个后端都说成个人独立完成，除非能拿出具体文件和 commit 证明。
- 项目中的代码证据：`git log --oneline`、`git shortlog -sne --all --no-merges`、`git commit 07b4d1b9`、`git commit cb4f59bb`、`git commit 18eacb88`、`docs/interview.md`
- 可能的连续追问：哪些模块是你主导的，哪些只是参与？如果被问到“你做了什么最难的部分”，你怎么答？
- 常见低质量回答：后端基本都是我做的。
- 难度：困难

#### J2. “独立完成” 这类词在这个仓库里能不能成立，你会怎么回答？
- 面试官问题：你能不能把“独立完成”说得站得住？
- 提问意图：看你是否会用证据约束自己的表达。
- 优秀回答应包含的关键点：代码和 git 历史显示这不是单人纯独立工程，至少不能直接把整个后端说成自己独立完成；更稳妥的说法是“我主要负责某些链路的设计与实现”，哪些部分能证明、哪些部分需要本人补充，要分清楚。
- 项目中的代码证据：`git log --oneline`、`git shortlog -sne --all --no-merges`、`docs/mock-interview-final.md`
- 可能的连续追问：如果你要举一个能证明“独立完成”的子模块，会是哪一个？
- 常见低质量回答：算是独立完成吧。
- 难度：困难

#### J3. 这项目里你如何和前端、测试协作，避免把后端接口做成“自嗨”？
- 面试官问题：后端怎么跟前端页面和安全验证脚本配合？
- 提问意图：看你是否有接口契约意识和联调意识。
- 优秀回答应包含的关键点：前端有独立聊天页和教育工具页，后端需要考虑 SSE、AbortController、返回格式和角色边界；测试侧则有安全脚本和 abort 脚本，说明你不是只写代码，还要保证可验证。
- 项目中的代码证据：`ruoyi-ui/src/views/ai/chat.vue`、`ruoyi-ui/src/views/ai/edu-tools.vue`、`benchmark/security/*.sh`、`docs/security-test.md`
- 可能的连续追问：前端停止按钮没生效时你怎么查？接口结构改过几次？
- 常见低质量回答：前端同学会配合。
- 难度：中等

## K. 如果重新设计会怎么做

#### K1. 如果让你重新设计，你会怎样把 RAG、工具调用和会话记忆统一起来？
- 面试官问题：再来一版，你会怎么组织这些 AI 能力？
- 提问意图：看你是否具备架构抽象能力，而不只是会堆代码。
- 优秀回答应包含的关键点：可以考虑加一个更清晰的编排层，把公共问答、学生工具和会话状态拆成策略与执行两层；但要保留权限边界、可追溯和可取消这几个硬约束。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`
- 可能的连续追问：哪些逻辑继续留在 controller，哪些提到 service 层？你会不会引入 agent？
- 常见低质量回答：全部放进一个 agent。
- 难度：困难

#### K2. 如果重新做知识库删除一致性，你会怎么补强？
- 面试官问题：当前删除链路还有什么可以改得更可靠？
- 提问意图：看你是否能提出工程化改进，而不是只说“再加异常处理”。
- 优秀回答应包含的关键点：可以把 `docId` 作为一等主键写进所有 chunk metadata，删除时优先按 `docId` 精准删除，并为 OSS / 向量 / DB 加幂等重试或状态机，而不是只靠 `source/fileName` fallback。
- 项目中的代码证据：`ruoyi-system/src/main/java/com/ruoyi/system/service/impl/KnowledgeDocServiceImpl.java#deleteSingleKnowledgeDoc`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java#deleteByFileUrl`、`docs/knowledge-delete-test.md`
- 可能的连续追问：删除是否要改成异步？重试状态放哪里？
- 常见低质量回答：加个 try-catch 就好。
- 难度：困难

#### K3. 如果你要补真正的性能和稳定性证据，你会怎么设计观测和压测？
- 面试官问题：如何把“感觉快了”变成可验证的指标？
- 提问意图：看你是否知道如何把工程问题量化。
- 优秀回答应包含的关键点：要补首包时间、流式总时长、断连率、资源释放率、Redis/DB/模型调用耗时等指标，并用现有 benchmark 模板逐步形成可复现报告；同时要明确目前仓库还没有完整的真实性能结论。
- 项目中的代码证据：`docs/benchmark.md`、`benchmark/ab/README.md`、`benchmark/jmeter/README.md`、`benchmark/security/sse-abort-test.js`
- 可能的连续追问：你会把这些指标埋在哪里？怎么区分一次请求里的检索慢还是模型慢？
- 常见低质量回答：跑一下压测就知道了。
- 难度：中等

## L. 针对目标岗位 JD 的问题

#### L1. 如果 JD 强调 Java / Spring Boot / Redis，你会拿这个项目哪几段来证明自己？
- 面试官问题：你怎么用这个项目证明自己不是只会写业务 CRUD？
- 提问意图：看你是否能把基础后端能力和项目代码对应起来。
- 优秀回答应包含的关键点：可以从启动入口、Security、Redis 缓存、Redis 会话记忆、向量存储、线程池和异常处理六个方面展开，逐个说明自己做过什么、解决了什么问题。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/RuoYiApplication.java`、`ruoyi-framework/src/main/java/com/ruoyi/framework/config/SecurityConfig.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/service/RedisChatMemory.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/SpringAiRedisVectorStoreConfig.java`、`ruoyi-framework/src/main/java/com/ruoyi/framework/config/ThreadPoolConfig.java`
- 可能的连续追问：哪一段是你亲手改的？你怎么证明自己对 Redis 不只是会用？
- 常见低质量回答：这些技术我都接触过。
- 难度：基础

#### L2. 如果 JD 强调 Spring AI / RAG / Function Calling，你怎么讲你在这个项目里的技术深度？
- 面试官问题：你对 `ChatClient`、`Advisor`、`ToolContext` 和 Function Calling 的理解有多深？
- 提问意图：看你是否真做过 AI 应用后端，而不是只贴了模型 API。
- 优秀回答应包含的关键点：能讲出 `QuestionAnswerAdvisor` 如何注入检索结果，`MessageChatMemoryAdvisor` 如何承接上下文，`ToolContext` 如何传后端身份，Function Calling 如何把“查什么”与“查谁”分开。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SseChatController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/EduAiFunctionConfig.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/RagService.java`
- 可能的连续追问：Advisor 和 ToolContext 的职责有什么区别？如果模型不按你的预期调用工具怎么办？
- 常见低质量回答：就是接了个大模型接口。
- 难度：困难

#### L3. 如果 JD 强调安全和权限边界，你会怎么用这个项目回答？
- 面试官问题：你做过什么能证明自己有安全意识？
- 提问意图：看你是否能把权限、身份、数据边界说清楚。
- 优秀回答应包含的关键点：公共/学生/管理员三条链路隔离，`@PreAuthorize` 控制角色，`CurrentStudentService` 从登录态复原身份，prompt injection 不应影响后端身份，配置里的敏感信息要承认仍有安全债。
- 项目中的代码证据：`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/EduApiController.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/AdminEduController.java`、`ruoyi-system/src/main/java/com/ruoyi/system/service/CurrentStudentService.java`、`docs/security-test.md`、`ruoyi-admin/src/main/resources/application.yml`
- 可能的连续追问：最容易出越权 bug 的地方在哪里？如果要再加强，你会怎么改？
- 常见低质量回答：用了 Spring Security。
- 难度：困难

#### L4. 如果 JD 强调交付和 ownership，你会怎么讲你的推进方式？
- 面试官问题：你怎么证明自己能推进事情落地，而不是只会写代码片段？
- 提问意图：看你是否能讲出迭代、验证、回归和协作。
- 优秀回答应包含的关键点：可以按“迁移旧链路、接入 RAG、做权限隔离、补 SSE 取消、补安全脚本”的顺序讲推进过程，同时说明每一阶段都留下了代码、脚本或文档证据。
- 项目中的代码证据：`git commit cb4f59bb`、`git commit 07b4d1b9`、`git commit 18eacb88`、`docs/interview.md`、`docs/mock-interview-final.md`
- 可能的连续追问：如果时间只够做一件事，你会先做哪条链路？为什么？
- 常见低质量回答：我负责把项目做完了。
- 难度：中等
