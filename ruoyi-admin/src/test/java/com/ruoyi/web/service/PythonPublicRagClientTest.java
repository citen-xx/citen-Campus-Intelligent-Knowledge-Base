package com.ruoyi.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.web.config.PythonAgentProperties;
import com.ruoyi.web.service.PythonPublicRagClient.HistoryMessage;
import com.ruoyi.web.service.PythonPublicRagClient.PythonRagEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class PythonPublicRagClientTest
{
    @Test
    void forwardsHistoryAndParsesPythonSseEvents() throws Exception
    {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext("/rag/public/stream", exchange -> {
            try (exchange)
            {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = ("data: {\"event\":\"answer\",\"answer\":\"增量\"}\n\n"
                    + "data: {\"event\":\"sources\",\"sources\":[{\"docId\":7,\"score\":0.9}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.setExecutor(executor);
        server.start();

        try
        {
            PythonAgentProperties properties = new PythonAgentProperties();
            properties.setStreamTimeout(Duration.ofSeconds(5));
            PythonPublicRagClient client = new PythonPublicRagClient(
                WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                new ObjectMapper(), properties);

            List<PythonRagEvent> events = client.stream("当前问题",
                List.of(new HistoryMessage("user", "上一问"), new HistoryMessage("assistant", "上一答")))
                .collectList().block(Duration.ofSeconds(5));

            assertNotNull(events);
            assertEquals(2, events.size());
            assertEquals("answer", events.get(0).event());
            assertEquals("增量", events.get(0).answer());
            assertEquals("sources", events.get(1).event());
            assertEquals(7L, events.get(1).sources().get(0).docId());
            assertEquals(0.9d, events.get(1).sources().get(0).score());
            assertTrue(requestBody.get().contains("\"history\""));
            assertTrue(requestBody.get().contains("上一问"));
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void studentStreamForwardsOpaqueCallbackHeaders() throws Exception
    {
        AtomicReference<String> callbackToken = new AtomicReference<>();
        AtomicReference<String> callbackConversation = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.createContext("/rag/student/stream", exchange -> {
            try (exchange)
            {
                callbackToken.set(exchange.getRequestHeaders().getFirst("X-AI-Callback-Token"));
                callbackConversation.set(exchange.getRequestHeaders().getFirst("X-AI-Callback-Conversation-Id"));
                byte[] response = ("data: {\"event\":\"answer\",\"answer\":\"学生回答\"}\n\n"
                    + "data: {\"event\":\"sources\",\"sources\":[]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.setExecutor(executor);
        server.start();

        try
        {
            PythonAgentProperties properties = new PythonAgentProperties();
            properties.setStreamTimeout(Duration.ofSeconds(5));
            PythonPublicRagClient client = new PythonPublicRagClient(
                WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                new ObjectMapper(), properties);

            List<PythonRagEvent> events = client.streamStudent("继续说明", List.of(), "opaque-token",
                "scoped-conversation").collectList().block(Duration.ofSeconds(5));

            assertNotNull(events);
            assertEquals(2, events.size());
            assertEquals("opaque-token", callbackToken.get());
            assertEquals("scoped-conversation", callbackConversation.get());
        }
        finally
        {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
