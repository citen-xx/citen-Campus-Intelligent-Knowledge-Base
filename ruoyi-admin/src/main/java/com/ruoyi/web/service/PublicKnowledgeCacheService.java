package com.ruoyi.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.service.KnowledgeBaseVersionService;
import com.ruoyi.web.service.PythonPublicRagClient.PythonRagSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PublicKnowledgeCacheService
{
    private static final String KEY_PREFIX = "ai:public-answer:";
    private static final String HIT_KEY = "ai:public-answer:metrics:hits";
    private static final String MISS_KEY = "ai:public-answer:metrics:misses";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseVersionService versionService;

    public PublicKnowledgeCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
        KnowledgeBaseVersionService versionService)
    {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.versionService = versionService;
    }

    public CachedPublicAnswer get(String question)
    {
        String key = buildKey(question);
        String payload = redisTemplate.opsForValue().get(key);
        if (payload == null)
        {
            redisTemplate.opsForValue().increment(MISS_KEY);
            return null;
        }
        try
        {
            CachedPublicAnswer answer = objectMapper.readValue(payload, new TypeReference<>() { });
            redisTemplate.opsForValue().increment(HIT_KEY);
            return new CachedPublicAnswer(answer.answer(), answer.sources() == null ? List.of() : answer.sources());
        }
        catch (Exception ex)
        {
            redisTemplate.delete(key);
            redisTemplate.opsForValue().increment(MISS_KEY);
            return null;
        }
    }

    public void put(String question, String answer, List<PythonRagSource> sources)
    {
        if (answer == null || answer.isBlank())
        {
            return;
        }
        try
        {
            String payload = objectMapper.writeValueAsString(
                new CachedPublicAnswer(answer, sources == null ? List.of() : sources));
            redisTemplate.opsForValue().set(buildKey(question), payload, TTL);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("公共知识答案缓存序列化失败", ex);
        }
    }

    public CacheMetrics metrics()
    {
        long hits = parseCounter(redisTemplate.opsForValue().get(HIT_KEY));
        long misses = parseCounter(redisTemplate.opsForValue().get(MISS_KEY));
        long total = hits + misses;
        return new CacheMetrics(hits, misses, total == 0 ? null : (double) hits / total);
    }

    private String buildKey(String question)
    {
        String normalized = question == null ? "" : question.trim().replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + "v" + versionService.currentVersion() + ":" + HexFormat.of().formatHex(digest);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("缓存键摘要生成失败", ex);
        }
    }

    private long parseCounter(String value)
    {
        try
        {
            return value == null ? 0L : Long.parseLong(value);
        }
        catch (NumberFormatException ex)
        {
            return 0L;
        }
    }

    public record CachedPublicAnswer(String answer, List<PythonRagSource> sources)
    {
    }

    public record CacheMetrics(long hits, long misses, Double hitRate)
    {
    }
}
