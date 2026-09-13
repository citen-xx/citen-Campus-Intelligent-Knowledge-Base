package com.ruoyi.web.service;

import java.time.Duration;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.web.config.PythonAgentProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class PythonPublicRagClient
{
    private static final String PUBLIC_RAG_STREAM_PATH = "/rag/public/stream";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration streamTimeout;

    public PythonPublicRagClient(@Qualifier("pythonAgentWebClient") WebClient webClient, ObjectMapper objectMapper,
        PythonAgentProperties properties)
    {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.streamTimeout = properties.getStreamTimeout();
    }

    public Flux<PythonRagEvent> stream(String question, List<HistoryMessage> history)
    {
        PublicRagRequest request = new PublicRagRequest(question, history == null ? List.of() : history);
        return request(PUBLIC_RAG_STREAM_PATH, request, null, null);
    }

    public Flux<PythonRagEvent> streamStudent(String question, List<HistoryMessage> history,
        String callbackToken, String conversationId)
    {
        PublicRagRequest request = new PublicRagRequest(question, history == null ? List.of() : history);
        return request("/rag/student/stream", request, callbackToken, conversationId);
    }

    private Flux<PythonRagEvent> request(String path, PublicRagRequest request, String callbackToken,
        String conversationId)
    {
        WebClient.RequestBodySpec requestSpec = webClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM);
        if (callbackToken != null)
        {
            requestSpec.header("X-AI-Callback-Token", callbackToken)
                .header("X-AI-Callback-Conversation-Id", conversationId);
        }
        return requestSpec.bodyValue(request).retrieve()
            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
            .concatMap(event -> decodeEvent(event.data()))
            .timeout(streamTimeout);
    }

    private Mono<PythonRagEvent> decodeEvent(String data)
    {
        if (data == null || data.isBlank())
        {
            return Mono.empty();
        }
        try
        {
            return Mono.just(objectMapper.readValue(data, PythonRagEvent.class));
        }
        catch (Exception ex)
        {
            return Mono.error(new IllegalStateException("Python RAG SSE event format is invalid", ex));
        }
    }

    private record PublicRagRequest(String question, List<HistoryMessage> history)
    {
    }

    public record HistoryMessage(String role, String content)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonRagEvent(String event, String answer, List<PythonRagSource> sources, String message)
    {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PythonRagSource(Long docId, String fileName, String section, Integer chunkIndex, String sourceUrl,
                                  Double score)
    {
    }
}
