package com.f1betting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Application-level Spring configuration.
 */
@Configuration
public class AppConfig {

    @Value("${openf1.api.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${openf1.api.read-timeout-ms:10000}")
    private int readTimeoutMs;

    /**
     * Shared {@link RestTemplate} bean used by all external HTTP adapters.
     * Timeouts are configurable via application.properties.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
