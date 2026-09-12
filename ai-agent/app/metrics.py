from dataclasses import dataclass
from threading import Lock


RAG_RETRIEVAL = "campus.ai.rag.retrieval"
LLM_STREAM = "campus.ai.llm.stream"
CHAT_FIRST_TOKEN = "campus.ai.chat.first-token"
METRIC_NAMES = (RAG_RETRIEVAL, LLM_STREAM, CHAT_FIRST_TOKEN)


@dataclass
class _TimerState:
    count: int = 0
    total_seconds: float = 0.0
    max_seconds: float = 0.0


class MetricsRegistry:
    """Small in-process timer registry for the standalone agent."""

    def __init__(self) -> None:
        self._lock = Lock()
        self._timers = {name: _TimerState() for name in METRIC_NAMES}

    def observe(self, name: str, duration_seconds: float) -> None:
        if name not in self._timers:
            raise ValueError(f"unsupported metric: {name}")
        duration = max(0.0, duration_seconds)
        with self._lock:
            timer = self._timers[name]
            timer.count += 1
            timer.total_seconds += duration
            timer.max_seconds = max(timer.max_seconds, duration)

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            metrics = {
                name: {
                    "count": timer.count,
                    "totalSeconds": timer.total_seconds,
                    "averageSeconds": (
                        timer.total_seconds / timer.count if timer.count else None
                    ),
                    "maxSeconds": timer.max_seconds if timer.count else None,
                }
                for name, timer in self._timers.items()
            }
        return {"metrics": metrics}
