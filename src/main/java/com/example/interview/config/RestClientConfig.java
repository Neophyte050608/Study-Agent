package com.example.interview.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;

/**
 * RestClient 配置类。
 * 
 * 该配置类用于自定义 Spring 6.1+ 引入的 RestClient，统一配置底层 HTTP 客户端的行为。
 */
@Configuration
public class RestClientConfig {

    /**
     * 配置 RestClient 的自定义设置。
     * 这里主要调整了底层 HTTP 请求工厂的超时时间。
     * 
     * @return RestClientCustomizer 实例
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return builder -> {
            // 使用 Java 11+ 自带的 HttpClient 作为请求工厂实现
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            // 设置读取超时时间为 120 秒，以支持大模型等耗时较长的 API 调用
            factory.setReadTimeout(Duration.ofSeconds(120));
            builder.requestFactory(factory);
        };
    }
}
