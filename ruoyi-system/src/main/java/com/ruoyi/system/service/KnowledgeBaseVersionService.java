package com.ruoyi.system.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseVersionService
{
    private static final String VERSION_KEY = "ai:knowledge:version";
    private final StringRedisTemplate redisTemplate;

    public KnowledgeBaseVersionService(StringRedisTemplate redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    public long currentVersion()
    {
        String value = redisTemplate.opsForValue().get(VERSION_KEY);
        if (value == null)
        {
            redisTemplate.opsForValue().setIfAbsent(VERSION_KEY, "1");
            value = redisTemplate.opsForValue().get(VERSION_KEY);
            if (value == null)
            {
                throw new IllegalStateException("无法初始化知识库版本");
            }
        }
        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ex)
        {
            throw new IllegalStateException("知识库版本数据损坏: " + value, ex);
        }
    }

    public long increment()
    {
        Long version = redisTemplate.opsForValue().increment(VERSION_KEY);
        return version == null ? currentVersion() : version;
    }
}
