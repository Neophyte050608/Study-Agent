package com.example.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * InterviewReview 应用程序主入口。
 * 
 * 职责：
 * 1. 启动 Spring Boot 应用。
 * 2. 自动配置与组件扫描。
 */
@SpringBootApplication
public class InterviewReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewReviewApplication.class, args);
    }

}
