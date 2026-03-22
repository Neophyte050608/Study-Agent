package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 统一异步线程池配置。
 * 
 * 职责：
 * 避免使用默认的 ForkJoinPool.commonPool()，防止 IO 密集型任务（如 RAG 检索、写画像）
 * 阻塞 JVM 核心线程池，从而提高系统的并发吞吐量与稳定性。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * RAG 混合检索专用线程池
     * 特点：响应要求高，包含向量库网络 IO，因此核心线程数适当调高，队列不宜过长。
     */
    @Bean(name = "ragRetrieveExecutor")
    public Executor ragRetrieveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("RagRetrieve-");
        // 拒绝策略：由调用线程（主线程）自己执行，保证降级但任务不丢
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 画像异步更新专用线程池
     * 特点：后台默默执行，允许排队，但绝不能影响前端用户的响应时间。
     */
    @Bean(name = "profileUpdateExecutor")
    public Executor profileUpdateExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ProfileUpdate-");
        // 拒绝策略：如果队列满了，让调用线程去执行，保证数据不丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}