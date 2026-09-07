package com.ruoyi.web.controller.system;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.service.ChatIntentRouter;
import com.ruoyi.system.service.ChatIntentRouter.ChatIntent;
import com.ruoyi.system.service.ChatIntentRouter.RouteDecision;
import com.ruoyi.system.service.CurrentStudentService;
import com.ruoyi.system.service.StudentBusinessToolService;
import com.ruoyi.web.service.ChatSessionScopeService;
import com.ruoyi.web.service.PublicKnowledgeCacheService;
import com.ruoyi.web.service.PublicKnowledgeCacheService.CachedPublicAnswer;
import com.ruoyi.web.service.PythonPublicRagClient;
import com.ruoyi.web.service.PythonPublicRagClient.HistoryMessage;
import com.ruoyi.web.service.PythonPublicRagClient.PythonRagEvent;
import com.ruoyi.web.service.PythonPublicRagClient.PythonRagSource;
import com.ruoyi.web.service.RedisChatMemory;
import com.ruoyi.web.service.RedisChatMemory.ChatMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SseChatControllerTest
{
    private static final String QUESTION = "奖学金申请条件";
    private static final String CONVERSATION_ID = "conversation-1";
    private static final String SCOPED_CONVERSATION_ID = "public:anonymous:session-1:conversation-1";

    private final RedisChatMemory chatMemory = mock(RedisChatMemory.class);
    private final ChatSessionScopeService scopeService = mock(ChatSessionScopeService.class);
    private final ChatIntentRouter router = mock(ChatIntentRouter.class);
    private final PublicKnowledgeCacheService cacheService = mock(PublicKnowledgeCacheService.class);
    private final PythonPublicRagClient pythonClient = mock(PythonPublicRagClient.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private SseChatController controller;

    @BeforeEach
    void setUp()
    {
        when(scopeService.scopedConversationId(eq(CONVERSATION_ID), eq(ChatSessionScopeService.ChatChannel.PUBLIC),
            eq(request))).thenReturn(SCOPED_CONVERSATION_ID);
        when(router.route(QUESTION, false)).thenReturn(new RouteDecision(ChatIntent.PUBLIC_KNOWLEDGE, null));

        controller = new SseChatController(chatMemory, new ObjectMapper(), mock(CurrentStudentService.class),
            mock(StudentBusinessToolService.class), scopeService, router, cacheService, pythonClient,
            new SimpleMeterRegistry(), null);
    }

    @Test
    void cacheHitDoesNotCallPython()
    {
        PythonRagSource source = new PythonRagSource(1L, "rules.pdf", "第一条", 0,
            "https://example.invalid", 0.9d);
        when(chatMemory.get(SCOPED_CONVERSATION_ID, 20)).thenReturn(List.of());
        when(cacheService.get(QUESTION)).thenReturn(new CachedPublicAnswer("cached answer", List.of(source)));

        controller.publicStreamChat(body(), null, null, null, request);

        verify(pythonClient, never()).stream(eq(QUESTION), anyList());
        verify(chatMemory).add(eq(SCOPED_CONVERSATION_ID), anyList());
    }

    @Test
    void cacheMissPersistsCompletedPythonResponse()
    {
        PythonRagSource source = new PythonRagSource(1L, "rules.pdf", "第一条", 0,
            "https://example.invalid", 0.9d);
        when(chatMemory.get(SCOPED_CONVERSATION_ID, 20)).thenReturn(List.of());
        when(cacheService.get(QUESTION)).thenReturn(null);
        when(pythonClient.stream(QUESTION, List.of())).thenReturn(Flux.just(
            new PythonRagEvent("answer", "streamed answer", null, null),
            new PythonRagEvent("sources", null, List.of(source), null)));

        controller.publicStreamChat(body(), null, null, null, request);

        verify(pythonClient).stream(QUESTION, List.of());
        verify(cacheService).put(eq(QUESTION), eq("streamed answer"), anyList());
        verify(chatMemory).add(eq(SCOPED_CONVERSATION_ID), anyList());
    }

    @Test
    void existingHistoryIsForwardedAndBypassesPublicCache()
    {
        List<HistoryMessage> expectedHistory = List.of(
            new HistoryMessage("user", "上一问"), new HistoryMessage("assistant", "上一答"));
        when(chatMemory.get(SCOPED_CONVERSATION_ID, 20)).thenReturn(List.of(
            ChatMessage.user("上一问"), ChatMessage.assistant("上一答")));
        when(pythonClient.stream(QUESTION, expectedHistory)).thenReturn(Flux.just(
            new PythonRagEvent("answer", "继续回答", null, null),
            new PythonRagEvent("sources", null, List.of(), null)));

        controller.publicStreamChat(body(), null, null, null, request);

        verify(cacheService, never()).get(QUESTION);
        verify(cacheService, never()).put(eq(QUESTION), eq("继续回答"), anyList());
        verify(pythonClient).stream(QUESTION, expectedHistory);
    }

    private Map<String, Object> body()
    {
        return Map.of("prompt", QUESTION, "conversationId", CONVERSATION_ID);
    }
}
