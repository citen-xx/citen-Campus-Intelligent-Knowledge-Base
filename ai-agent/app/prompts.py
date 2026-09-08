from app.vector_store import RetrievedChunk


NO_CONTEXT_SYSTEM_PROMPT = """你是校园智能助手。当前没有检索到足够的校园知识依据。
请明确回答“当前知识库中没有检索到足够信息”，不要编造制度内容。
"""


def build_system_prompt(retrieved_chunks: list[RetrievedChunk]) -> str:
    if not retrieved_chunks:
        return NO_CONTEXT_SYSTEM_PROMPT

    context = "\n\n--------------------\n\n".join(
        _format_chunk(chunk) for chunk in retrieved_chunks
    )
    return f"""你是校园智能助手。请只依据下面检索到的校园知识片段回答。
片段不足时明确说明知识库信息不足，不要编造事实。
回答正文不要伪造来源编号；来源由系统另行以结构化数据返回。

检索片段：
{context}
"""


def _format_chunk(chunk: RetrievedChunk) -> str:
    metadata = chunk.metadata
    return (
        f"[docId={_java_value(metadata.get('docId'))}, "
        f"文件={_java_value(metadata.get('fileName'))}, "
        f"章节={_java_value(metadata.get('section'))}, "
        f"chunk={_java_value(metadata.get('chunkIndex'))}]\n"
        f"{chunk.content}"
    )


def _java_value(value: object) -> str:
    return "null" if value is None else str(value)

