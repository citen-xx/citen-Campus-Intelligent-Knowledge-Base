package com.ruoyi.web.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campus.ai.python-agent")
public class PythonAgentProperties
{
    private String baseUrl = "http://127.0.0.1:8090";

    private int connectTimeoutMs = 5_000;

    private Duration streamTimeout = Duration.ofSeconds(55);

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs()
    {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs)
    {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public Duration getStreamTimeout()
    {
        return streamTimeout;
    }

    public void setStreamTimeout(Duration streamTimeout)
    {
        this.streamTimeout = streamTimeout;
    }
}
