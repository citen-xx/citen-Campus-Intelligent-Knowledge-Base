# RAG 与工程指标评测

本目录只提供评测程序、数据格式和结果落盘约定，不包含预先填写的命中率、延迟、缓存命中率或安全结论。

## Ground Truth

正式数据文件为 `evaluation/data/ground-truth.json`。一条问题可以标注多个正确来源：

```json
{
  "id": "q001",
  "question": "国家奖学金申请需要满足什么条件？",
  "expectedSources": [
    { "docId": "数据库中的真实 docId", "section": "申请条件" },
    { "docId": "另一个可接受的真实 docId", "section": "第三章 申请条件" }
  ]
}
```

`section` 可留空，此时只比较 `docId`。正式数据禁止使用 `_example` 开头的 ID。当前仓库没有原先人工标注的 500 条数据，必须把真实数据导入后才能计算或引用 HitRate。

评测器位于 `ruoyi-admin/src/test/java/com/ruoyi/evaluation/RagEvaluationRunnerTest.java`。未来明确执行时使用：

```bash
mvn -pl ruoyi-admin -am -Dtest=RagEvaluationRunnerTest \
  -Drag.evaluation.enabled=true \
  -Drag.evaluation.dataset=evaluation/data/ground-truth.json \
  -Drag.evaluation.output=evaluation/results/rag-evaluation-result.json test
```

它调用已启动 Python Agent 的 `POST /rag/retrieve` 获取真实 Top-5，计算 `HitRate@1`、`HitRate@3`、`HitRate@5`，并落盘总样本数、命中/失败数以及每条案例的实际召回来源。通过 `PYTHON_AGENT_BASE_URL` 或 `-Drag.evaluation.agent-url=http://...` 指定 Agent。空数据集、示例 ID 或不完整标注会在网络调用和结果写入前直接失败，不会生成 `0%` 或其他误导结果。

## 参数对比

固定同一份 Ground Truth。修改 `RAG_CHUNK_SIZE`、`RAG_OVERLAP_CHARS` 后必须由 Java 重新导入索引；修改 Python 的 `RAG_SIMILARITY_THRESHOLD` 后重启 Agent。评测端始终显式请求 Top-5，不能用生产 `RAG_TOP_K` 截断 `HitRate@5`。只有拿到真实结果后再选方案，不在代码里预设“最佳命中率”。

## 安全与 SSE

`security-regression.mjs` 使用两个真实学生 Token 验证匿名拒绝、callback token 过期/篡改拒绝、callback token 不可跨 `conversationId` 重放、相同 `conversationId` 的 A/B 隔离、伪造 studentId 不改变登录身份、当前账号余额、个人请求不触碰公开缓存、公开 cache miss→hit、Python 指标可见、正常 SSE、主动中断和学生入口限流。运行前提供 `USER_A_TOKEN`、`USER_B_TOKEN`、`ADMIN_TOKEN`、`CALLBACK_TOKEN_SECRET`、`TOOL_TEST_SUBJECT`、`USER_A_EXPECTED_SCORE`、`USER_B_EXPECTED_SCORE`、`USER_A_EXPECTED_BALANCE`、`PUBLIC_TEST_QUESTION`，并按需设置 `BASE_URL`、`PYTHON_AGENT_BASE_URL`；两个账号在指定课程上的真实已知成绩必须不同。脚本需要已启动的完整环境，结果保存到 `evaluation/results/`。

## 延迟与缓存

`performance-benchmark.mjs` 通过 Java 的 `/api/ai/chat/public/stream` 对固定 workload 先预热再重复请求，保存每次首 Token 与完整响应耗时，并计算样本数、平均值、P50、P95。首 Token 严格定义为客户端收到首个 SSE `answer` 事件；整条流没有 `answer` 时脚本失败。传入管理员 Token 时还会读取真实缓存 hit/miss 计数。脚本没有默认性能数字。

```powershell
$env:BASE_URL="http://127.0.0.1:8080"
$env:PYTHON_AGENT_BASE_URL="http://127.0.0.1:8090"
node evaluation/security-regression.mjs
node evaluation/performance-benchmark.mjs
```
