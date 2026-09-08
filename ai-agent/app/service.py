from collections.abc import AsyncIterator
import json
from time import perf_counter
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.messages import ToolMessage
from langchain_core.tools import StructuredTool
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
import httpx
from redis.asyncio import Redis

from app.config import Settings
from app.metrics import CHAT_FIRST_TOKEN, LLM_STREAM, RAG_RETRIEVAL, MetricsRegistry
from app.models import CardBalanceToolInput, HistoryMessage, StudentScoreToolInput
from app.prompts import build_system_prompt
from app.vector_store import RedisVectorRetriever, RetrievedChunk


class PublicRagService:
    def __init__(
        self,
        settings: Settings,
        redis_client: Redis,
        embeddings: Any | None = None,
        chat_model: Any | None = None,
        metrics: MetricsRegistry | None = None,
    ):
        self._redis_client = redis_client
        self._settings = settings
        self._metrics = metrics or MetricsRegistry()
        self._embeddings = embeddings or OpenAIEmbeddings(
            model=settings.embedding_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.openai_base_url,
        )
        self._chat_model = chat_model or ChatOpenAI(
            model=settings.chat_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.openai_base_url,
            streaming=True,
        )
        self._retriever = RedisVectorRetriever(
            redis_client, self._embeddings, settings
        )

    @property
    def metrics(self) -> MetricsRegistry:
        return self._metrics

    @property
    def similarity_threshold(self) -> float:
        return self._settings.similarity_threshold

    async def retrieve(
        self, question: str, top_k: int | None = None
    ) -> list[RetrievedChunk]:
        started = perf_counter()
        try:
            return await self._retriever.retrieve(question, top_k)
        finally:
            self._metrics.observe(RAG_RETRIEVAL, perf_counter() - started)

    async def stream_answer(
        self,
        question: str,
        retrieved_chunks: list[RetrievedChunk],
        history: list[HistoryMessage] | None = None,
    ) -> AsyncIterator[str]:
        messages = [
            SystemMessage(content=build_system_prompt(retrieved_chunks)),
            *(_to_langchain_message(message) for message in history or []),
            HumanMessage(content=question),
        ]
        started = perf_counter()
        first_token_recorded = False
        try:
            async for chunk in self._chat_model.astream(messages):
                text = _chunk_text(chunk)
                if text:
                    if not first_token_recorded:
                        self._metrics.observe(CHAT_FIRST_TOKEN, perf_counter() - started)
                        first_token_recorded = True
                    yield text
        finally:
            self._metrics.observe(LLM_STREAM, perf_counter() - started)

    async def stream_student_answer(
        self,
        question: str,
        retrieved_chunks: list[RetrievedChunk],
        history: list[HistoryMessage] | None,
        callback_token: str,
        callback_conversation_id: str,
    ) -> AsyncIterator[str]:
        async def score_callback(subject: str) -> dict[str, Any]:
            return await self._call_java_tool(
                "/internal/ai/tool/student-score", {"subject": subject}, callback_token, callback_conversation_id
            )

        async def balance_callback() -> dict[str, Any]:
            return await self._call_java_tool(
                "/internal/ai/tool/card-balance", {}, callback_token, callback_conversation_id
            )

        student_tool_model = self._chat_model.bind_tools(
            [
                StructuredTool.from_function(
                    coroutine=score_callback,
                    name="getStudentScore",
                    description="查询当前登录学生本人的课程成绩，只接收课程名称。",
                    args_schema=StudentScoreToolInput,
                ),
                StructuredTool.from_function(
                    coroutine=balance_callback,
                    name="getCardBalance",
                    description="查询当前登录学生本人的一卡通余额，不接收任何参数。",
                    args_schema=CardBalanceToolInput,
                ),
            ]
        )
        messages = [
            SystemMessage(content=_student_system_prompt(retrieved_chunks)),
            *(_to_langchain_message(message) for message in history or []),
            HumanMessage(content=question),
        ]
        started = perf_counter()
        first_token_recorded = False
        try:
            # Tool calls are resolved only through Java callbacks. The model never receives an identity field.
            for _ in range(3):
                response = await student_tool_model.ainvoke(messages)
                messages.append(response)
                tool_calls = getattr(response, "tool_calls", None) or []
                if not tool_calls:
                    text = _chunk_text(response)
                    if text:
                        if not first_token_recorded:
                            self._metrics.observe(CHAT_FIRST_TOKEN, perf_counter() - started)
                            first_token_recorded = True
                        yield text
                    return
                for call in tool_calls:
                    tool_name = call.get("name")
                    tool_args = call.get("args") or {}
                    if tool_name == "getStudentScore":
                        # Only the allow-listed subject reaches Java; any model-supplied identity fields are ignored.
                        result = await score_callback(subject=str(tool_args.get("subject", "")))
                    elif tool_name == "getCardBalance":
                        # Balance has an intentionally empty argument contract.
                        result = await balance_callback()
                    else:
                        result = {"status": "BAD_REQUEST", "message": "不支持的工具"}
                    messages.append(ToolMessage(content=_tool_result_text(result), tool_call_id=call["id"]))
            raise RuntimeError("student tool call limit exceeded")
        finally:
            self._metrics.observe(LLM_STREAM, perf_counter() - started)
    async def _call_java_tool(self, path: str, payload: dict[str, Any], callback_token: str,
        callback_conversation_id: str) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                f"{self._settings.java_callback_base_url}{path}",
                json=payload,
                headers={
                    "X-AI-Callback-Token": callback_token,
                    "X-AI-Callback-Conversation-Id": callback_conversation_id,
                },
            )
            response.raise_for_status()
            data = response.json()
            status = data.get("status")
            if status != "SUCCESS":
                return {"status": status or "ERROR", "message": data.get("msg", "个人数据查询失败"), "data": data.get("data")}
            return data

    async def close(self) -> None:
        await self._redis_client.aclose()


def _to_langchain_message(message: HistoryMessage):
    if message.role == "assistant":
        return AIMessage(content=message.content)
    if message.role == "system":
        return SystemMessage(content=message.content)
    return HumanMessage(content=message.content)


def _chunk_text(chunk: Any) -> str:
    content = getattr(chunk, "content", "")
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""

    parts: list[str] = []
    for block in content:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and isinstance(block.get("text"), str):
            parts.append(block["text"])
    return "".join(parts)


def _student_system_prompt(retrieved_chunks: list[RetrievedChunk]) -> str:
    return (
        build_system_prompt(retrieved_chunks)
        + "\n学生频道规则：成绩和一卡通问题优先调用对应工具。工具只查询当前登录学生，"
        "不要尝试从用户问题、工具参数或上下文推断或传入 studentId/userId。"
    )


def _tool_result_text(result: dict[str, Any]) -> str:
    return json.dumps(result, ensure_ascii=False, separators=(",", ":"))
