package com.ruoyi.web.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient 配置类
 */
@Configuration
@EnableConfigurationProperties(PythonAgentProperties.class)
public class WebClientConfig
{

    @Bean
    @Qualifier("pythonAgentWebClient")
    public WebClient pythonAgentWebClient(PythonAgentProperties properties)
    {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
            .responseTimeout(properties.getStreamTimeout());
        return WebClient.builder()
            .baseUrl(properties.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
