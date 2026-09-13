# Python RAG Service

该目录是公开知识问答的独立 Python 服务。Java 仍负责认证、限流、路由、缓存和身份解析；本服务只负责查询向量化、RediSearch KNN 检索、Prompt 组装和模型流式生成。

## 启动

要求 Python 3.11/3.12、Redis Stack/RediSearch，以及已由 Java 导入管线写入的向量索引。

PowerShell：

```powershell
cd ai-agent
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt

$env:DASHSCOPE_API_KEY="your-key"
$env:DASHSCOPE_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode"
$env:VECTOR_REDIS_URI="redis://localhost:6379"
$env:AI_AGENT_HOST="127.0.0.1"
$env:AI_AGENT_PORT="8090"
.\start.ps1
```

macOS/Linux：

```bash
cd ai-agent
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
export DASHSCOPE_API_KEY="your-key"
export AI_AGENT_HOST="127.0.0.1"
export AI_AGENT_PORT="8090"
chmod +x start.sh
./start.sh
```

请求示例：

```bash
curl -N -X POST http://127.0.0.1:8090/rag/public/stream \
  -H "Content-Type: application/json" \
  -d '{"question":"学校奖学金的申请条件是什么？"}'
```

Java 代理会额外传递最近的会话历史；`history` 可选，省略时保持无状态首轮请求兼容：

```json
{
  "question": "继续说明上一条规则",
  "history": [
    {"role": "user", "content": "上一条问题"},
    {"role": "assistant", "content": "上一条回答"}
  ]
}
```

学生频道使用独立的 `/rag/student/stream` 入口。Java 通过 `X-AI-Callback-Token` 和
`X-AI-Callback-Conversation-Id` 传递短时 callback 凭据；Python 只使用 LangChain 白名单工具选择操作，
再用同一凭据回调 Java 的 `/internal/ai/tool/student-score` 或 `/internal/ai/tool/card-balance`。
Python 服务不连接 MySQL，也不接受或生成 `studentId`/`userId` 工具参数。

评测器使用只检索、不生成回答的内部接口：

```bash
curl -X POST http://127.0.0.1:8090/rag/retrieve \
  -H "Content-Type: application/json" \
  -d '{"question":"学校奖学金的申请条件是什么？","topK":5}'
```

该接口与聊天共用查询 Embedding、RediSearch schema、分数换算和相似度阈值，返回有序 `sources`。

输出沿用 Java 当前数据格式：

```text
data: {"event":"answer","answer":"增量文本"}

data: {"event":"sources","sources":[{"docId":1,"fileName":"rules.pdf","section":"第一条","chunkIndex":0,"sourceUrl":"https://...","score":0.9}]}
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 无 | 必填，不得写入仓库 |
| `DASHSCOPE_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` | 与 Java `spring.ai.openai.base-url` 相同；传给 OpenAI SDK 前自动补 `/v1` |
| `DASHSCOPE_CHAT_MODEL` | `qwen-plus` | Python 聊天模型 |
| `DASHSCOPE_EMBEDDING_MODEL` | `text-embedding-v3` | 必须与 Java 导入侧模型一致 |
| `VECTOR_REDIS_URI` | `redis://localhost:6379` | 与 Java VectorStore 使用同一 URI/数据库 |
| `VECTOR_INDEX_NAME` | `campus_knowledge_v2` | RediSearch 索引名 |
| `VECTOR_KEY_PREFIX` | `campus_knowledge_v2` | JSON Key 前缀，Java 当前不会自动添加冒号 |
| `RAG_TOP_K` | `3` | 允许范围 1-20 |
| `RAG_SIMILARITY_THRESHOLD` | `0.6` | 允许范围 0-1 |
| `AI_AGENT_REDIS_TIMEOUT_SECONDS` | `10` | Redis 连接与命令超时 |
| `AI_AGENT_HOST` | `127.0.0.1` | 监听地址 |
| `AI_AGENT_PORT` | `8090` | 监听端口 |
| `JAVA_CALLBACK_BASE_URL` | `http://127.0.0.1:8080` | Java callback 接口地址；不存放 callback secret |

## 与 Java schema 对齐

实现依据 `SpringAiRedisVectorStoreConfig` 和仓库使用的 Spring AI `1.0.0-M6`：

- 索引类型为 JSON，Key 前缀使用 `VECTOR_KEY_PREFIX`。
- 正文字段是 `$.content AS content`。
- 向量字段是 `$.embedding AS embedding`，算法 `HNSW`、类型 `FLOAT32`、距离度量 `COSINE`。
- 向量维度不在 Python 侧猜测：服务读取 `FT.INFO` 中的 `DIM`，并校验 `text-embedding-v3` 查询向量长度完全一致。
- metadata 在 Redis JSON 顶层展开：`docId NUMERIC`、`documentType TAG`、`contentHash TAG`、`fileName/section/sourceUrl TEXT`。
- `chunkIndex` 由 Java 写入 JSON，但没有加入索引 schema；服务使用 `RETURN $.chunkIndex AS chunkIndex` 投影读取，不创建或修改索引。
- KNN 先取 Top-K，再按 Java Spring AI M6 的 `score=(2-cosineDistance)/2` 应用相似度阈值。
- 无命中时仍调用模型，但 System Prompt 明确要求回答知识库信息不足且不得编造，语义与 `RagService.buildSystemPrompt` 一致。

服务启动后会在首次请求时校验上述 schema；索引、前缀、字段类型、算法、维度或距离不一致会直接报错，不会静默降级。

## 指标

`GET /metrics` 返回轻量 JSON timer：

- `campus.ai.rag.retrieval`：查询 Embedding 和 RediSearch 检索总耗时。
- `campus.ai.llm.stream`：模型生成开始到结束、异常或取消。
- `campus.ai.chat.first-token`：模型生成开始到首个非空回答片段。

每项包含 `count`、`totalSeconds`、`averageSeconds`、`maxSeconds`；无样本时不会推导平均值。指标属于当前 Python 进程，重启清零，多 worker 由外部采集按实例聚合。

## 测试

```bash
python -m unittest discover -s tests -v
```
