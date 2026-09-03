package com.ruoyi.web.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Stores scoped conversation history without coupling chat runtime code to Spring AI. */
@Component
public class RedisChatMemory
{
    private static final String KEY_PREFIX = "ai:chat:memory:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final int MAX_HISTORY_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper)
    {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public void add(String conversationId, List<ChatMessage> messages)
    {
        if (conversationId == null || conversationId.isBlank() || messages == null || messages.isEmpty())
        {
            return;
        }

        String key = buildKey(conversationId);
        List<String> payloads = new ArrayList<>();
        for (ChatMessage message : messages)
        {
            if (message != null)
            {
                payloads.add(serialize(message));
            }
        }
        if (payloads.isEmpty())
        {
            return;
        }

        stringRedisTemplate.opsForList().rightPushAll(key, payloads);
        stringRedisTemplate.expire(key, TTL);
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY_SIZE)
        {
            stringRedisTemplate.opsForList().trim(key, -MAX_HISTORY_SIZE, -1);
        }
    }

    public List<ChatMessage> get(String conversationId, int lastN)
    {
        if (conversationId == null || conversationId.isBlank())
        {
            return List.of();
        }

        int fetchSize = lastN > 0 ? lastN : MAX_HISTORY_SIZE;
        String key = buildKey(conversationId);
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size == null || size <= 0)
        {
            return List.of();
        }

        long start = Math.max(0, size - fetchSize);
        List<String> payloads = stringRedisTemplate.opsForList().range(key, start, -1);
        if (payloads == null || payloads.isEmpty())
        {
            return List.of();
        }

        List<ChatMessage> messages = new ArrayList<>();
        for (String payload : payloads)
        {
            ChatMessage message = deserialize(payload);
            if (message != null)
            {
                messages.add(message);
            }
        }
        return messages;
    }

    public void clear(String conversationId)
    {
        if (conversationId != null && !conversationId.isBlank())
        {
            stringRedisTemplate.delete(buildKey(conversationId));
        }
    }

    private String buildKey(String conversationId)
    {
        return KEY_PREFIX + conversationId;
    }

    private String serialize(ChatMessage message)
    {
        try
        {
            return objectMapper.writeValueAsString(new StoredMessage(message.role().name(), message.content()));
        }
        catch (Exception e)
        {
            throw new RuntimeException("序列化聊天消息失败", e);
        }
    }

    private ChatMessage deserialize(String payload)
    {
        try
        {
            StoredMessage stored = objectMapper.readValue(payload, StoredMessage.class);
            if (stored == null || stored.type == null)
            {
                return null;
            }
            ChatRole role = ChatRole.valueOf(stored.type.toUpperCase(Locale.ROOT));
            return new ChatMessage(role, Objects.toString(stored.text, ""));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public enum ChatRole
    {
        USER,
        ASSISTANT,
        SYSTEM
    }

    public record ChatMessage(ChatRole role, String content)
    {
        public ChatMessage
        {
            Objects.requireNonNull(role, "role");
            content = Objects.toString(content, "");
        }

        public static ChatMessage user(String content)
        {
            return new ChatMessage(ChatRole.USER, content);
        }

        public static ChatMessage assistant(String content)
        {
            return new ChatMessage(ChatRole.ASSISTANT, content);
        }
    }

    public static class StoredMessage
    {
        public String type;
        public String text;

        public StoredMessage()
        {
        }

        public StoredMessage(String type, String text)
        {
            this.type = type;
            this.text = text;
        }

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getText()
        {
            return text;
        }

        public void setText(String text)
        {
            this.text = text;
        }
    }
}
