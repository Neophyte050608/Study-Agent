package com.example.interview.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return builder -> {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(120));
            builder.requestFactory(factory);
        };
    }
}
