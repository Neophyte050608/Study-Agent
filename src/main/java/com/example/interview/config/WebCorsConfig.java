package com.example.interview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域资源共享（CORS）配置类。
 *
 * <p>配置详情：</p>
 * <ul>
 *   <li><b>作用范围</b>：仅作用于 /api/** 路径下的接口。</li>
 *   <li><b>多域支持</b>：支持在配置文件中通过逗号分隔配置多个允许的 Origin。</li>
 *   <li><b>安全约束</b>：默认关闭了 allowCredentials，防止在跨域场景下意外泄露敏感的 Cookie。</li>
 * </ul>
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    /** 允许跨域的源地址列表，支持从属性文件读取，默认为本地开发地址 */
    @Value("${app.security.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    /**
     * 配置跨域映射规则。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 将配置的逗号分隔字符串解析为列表：例如 "http://localhost:8080,http://127.0.0.1:8080"
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
                
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins.toArray(String[]::new))
                // 设置允许的 HTTP 方法
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                // 允许携带所有请求头
                .allowedHeaders("*")
                // 不允许携带认证信息（如 Cookie），增加 API 的安全性
                .allowCredentials(false);
    }
}
