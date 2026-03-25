package com.example.interview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 安全配置类（Spring Security）。
 *
 * <p>当前项目的安全策略：</p>
 * <ul>
 *   <li><b>单机工具模式</b>：为了方便个人本地使用，目前将所有接口设置为完全开放（permitAll）。</li>
 *   <li><b>无状态设计</b>：禁用 Session 和 CSRF，符合 RESTful API 的设计规范。</li>
 *   <li><b>JWT 预留</b>：保留了 JWT 解码器和转换器逻辑，方便未来随时开启严格的鉴权机制。</li>
 *   <li><b>权限映射</b>：支持从 JWT 的 roles claim 中自动提取并映射为 Spring Security 的 ROLE_ 权限。</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    /**
     * 配置安全过滤链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 统一安全入口配置
        http
                // 启用 CORS 支持，以便 Spring Security 与 WebMvcConfigurer 的跨域配置协同工作
                .cors(org.springframework.security.config.Customizer.withDefaults())
                // 禁用 CSRF 防护，因为 API 调用通常不依赖浏览器 Cookie
                .csrf(AbstractHttpConfigurer::disable)
                // 设置为无状态会话策略，系统不会在服务器端存储任何用户会话信息
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 请求授权配置
                .authorizeHttpRequests(auth -> auth
                        // 针对单机版，放行所有请求
                        .anyRequest().permitAll()
                )
                // 配置 OAuth2 资源服务器支持，使用 JWT 鉴权
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * 配置 JWT 解码器。
     * 
     * @param secret 从配置文件读取的 JWT 签名密钥
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.security.jwt-secret}") String secret) {
        // 使用 HMAC-SHA256 算法和对称密钥解码 JWT
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * 配置 JWT 认证转换器，用于自定义角色和权限的提取逻辑。
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // 设置自定义的权限提取器
        converter.setJwtGrantedAuthoritiesConverter(this::extractAuthorities);
        return converter;
    }

    /**
     * 从 JWT Token 中提取角色信息并转换为 Spring Security 的 GrantedAuthority。
     * 
     * @param jwt JWT 对象
     * @return 提取出的权限集合
     */
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        // 假设 JWT 的 Payload 中存在名为 "roles" 的列表字段
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    // 将角色名添加 ROLE_ 前缀后映射为权限对象
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim()));
                }
            }
        }
        return authorities;
    }
}
