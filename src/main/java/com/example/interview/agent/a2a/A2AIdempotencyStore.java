package com.example.interview.agent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;
import java.util.HashSet;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A 消息幂等存储。
 *
 * <p>用于在 publish 阶段做“按消息唯一键去重”，避免重复投递导致的副作用（重复计费、重复写画像、重复日志等）。</p>
 *
 * <p>唯一键构成：</p>
 * <ul>
 *   <li>dayBucket：按天分桶（便于自然过期，也避免 key 无限增长）。</li>
 *   <li>capability/source：按能力/来源分桶（降低不同业务间的互相影响）。</li>
 *   <li>messageId：消息自身唯一ID（重试时应重新生成）。</li>
 * </ul>
 *
 * <p>后端策略：</p>
 * <ul>
 *   <li>优先 Redis（auto/redis 且可用）：利用 setIfAbsent + TTL 保证跨实例去重。</li>
 *   <li>Redis 不可用时自动熔断并回退内存：避免每次 publish 都触发 Redis 异常。</li>
 * </ul>
 */
@Component
public class A2AIdempotencyStore {

    private static final Logger logger = LoggerFactory.getLogger(A2AIdempotencyStore.class);

    private final Map<String, Long> processed = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final int maxSize;
    private final StringRedisTemplate redisTemplate;
    private final boolean preferRedis;
    private final String redisKeyPrefix;
    private final AtomicBoolean warned = new AtomicBoolean(false);
    private volatile boolean redisDisabled = false;
    private volatile long lastPurgeTime = 0;
    private static final long PURGE_INTERVAL_MS = 60_000;

    @Autowired
    public A2AIdempotencyStore(
            @Value("${app.a2a.idempotency.ttl-seconds:300}") long ttlSeconds,
            @Value("${app.a2a.idempotency.max-size:20000}") int maxSize,
            @Value("${app.a2a.idempotency.backend:auto}") String backend,
            @Value("${app.a2a.idempotency.redis-key-prefix:a2a:idempotency:}") String redisKeyPrefix,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this.ttlMillis = Math.max(30, ttlSeconds) * 1000L;
        this.maxSize = Math.max(1000, maxSize);
        String normalized = backend == null ? "auto" : backend.trim().toLowerCase(Locale.ROOT);
        this.preferRedis = "redis".equals(normalized) || "auto".equals(normalized);
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisKeyPrefix = redisKeyPrefix == null || redisKeyPrefix.isBlank() ? "a2a:idempotency:" : redisKeyPrefix;
    }

    public A2AIdempotencyStore(long ttlSeconds, int maxSize) {
        this.ttlMillis = Math.max(30, ttlSeconds) * 1000L;
        this.maxSize = Math.max(1000, maxSize);
        this.preferRedis = false;
        this.redisTemplate = null;
        this.redisKeyPrefix = "a2a:idempotency:";
    }

    /**
     * 仅按 messageId 去重（默认 capability/source）。
     */
    public boolean shouldProcess(String messageId) {
        return shouldProcessKey(buildKey(messageId, "default", "default"));
    }

    /**
     * 按消息元数据（capability/source）+ messageId 去重。
     *
     * <p>返回 true 表示“第一次见到，可处理”；返回 false 表示“已处理过，应丢弃”。</p>
     */
    public boolean shouldProcess(A2AMessage message) {
        if (message == null) {
            return true;
        }
        String capability = message.metadata() == null || message.metadata().capability() == null
                ? "default"
                : message.metadata().capability();
        String source = message.metadata() == null || message.metadata().source() == null
                ? "default"
                : message.metadata().source();
        return shouldProcessKey(buildKey(message.messageId(), capability, source));
    }

    private boolean shouldProcessKey(String uniqueKey) {
        if (uniqueKey == null || uniqueKey.isBlank()) {
            return true;
        }

        // Redis 路径：跨实例一致去重；如果 Redis 故障则熔断为内存路径，避免每次都报错。
        if (preferRedis && !redisDisabled && redisTemplate != null) {
            try {
                Boolean accepted = redisTemplate.opsForValue().setIfAbsent(uniqueKey, "1", Duration.ofMillis(ttlMillis));
                if (Boolean.TRUE.equals(accepted)) {
                    return true;
                }
                if (Boolean.FALSE.equals(accepted)) {
                    return false;
                }
            } catch (RuntimeException e) {
                redisDisabled = true;
                if (warned.compareAndSet(false, true)) {
                    logger.warn("Redis 幂等存储不可用，自动回退内存幂等。原因: {}", e.getMessage());
                }
            }
        }
        return shouldProcessInMemory(uniqueKey);
    }

    private String buildKey(String messageId, String capability, String source) {
        if (messageId == null || messageId.isBlank()) {
            return "";
        }
        String normalizedCapability = capability == null || capability.isBlank() ? "default" : capability.trim().toLowerCase(Locale.ROOT);
        String normalizedSource = source == null || source.isBlank() ? "default" : source.trim().toLowerCase(Locale.ROOT);
        String dayBucket = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return redisKeyPrefix + dayBucket + ":" + normalizedCapability + ":" + normalizedSource + ":" + messageId;
    }

    private boolean shouldProcessInMemory(String uniqueKey) {
        // 内存路径：只保证进程内去重；会定期清理过期键并在超出 maxSize 时做一次“近似清理”。
        long now = Instant.now().toEpochMilli();
        if (now - lastPurgeTime > PURGE_INTERVAL_MS) {
            purgeExpired();
            lastPurgeTime = now;
        }
        Long existing = processed.putIfAbsent(uniqueKey, now);
        if (existing != null) {
            return false;
        }
        if (processed.size() > maxSize) {
            purgeOldest();
        }
        return true;
    }

    private void purgeExpired() {
        // 严格按 TTL 清理：遍历并删除超过 ttlMillis 的键。
        long now = Instant.now().toEpochMilli();
        Iterator<Map.Entry<String, Long>> iterator = processed.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > ttlMillis) {
                iterator.remove();
            }
        }
    }

    private void purgeOldest() {
        // 近似清理：只删除“明显很旧”的键（阈值=ttl/2），不做严格 LRU，以控制遍历成本。
        long threshold = Instant.now().toEpochMilli() - ttlMillis / 2;
        Iterator<Map.Entry<String, Long>> iterator = processed.entrySet().iterator();
        while (iterator.hasNext() && processed.size() > maxSize) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < threshold) {
                iterator.remove();
            }
        }
    }

    public Map<String, Object> snapshot() {
        long redisKeys = 0;
        boolean redisAvailable = redisTemplate != null && !redisDisabled;
        if (redisAvailable) {
            try {
                // Approximate redis key count for idempotency
                ScanOptions options = ScanOptions.scanOptions().match(redisKeyPrefix + "*").count(100).build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    while (cursor.hasNext()) {
                        cursor.next();
                        redisKeys++;
                    }
                }
            } catch (Exception e) {
                redisAvailable = false;
            }
        }

        return Map.of(
                "preferRedis", preferRedis,
                "redisAvailable", redisAvailable,
                "inMemorySize", processed.size(),
                "redisSize", redisKeys,
                "ttlMillis", ttlMillis,
                "maxSize", maxSize,
                "redisKeyPrefix", redisKeyPrefix
        );
    }

    public Map<String, Object> clear(String scope, String keyContains) {
        String normalizedScope = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        String contains = keyContains == null ? "" : keyContains.trim().toLowerCase(Locale.ROOT);
        long clearedMemory = 0;
        long clearedRedis = 0;
        if ("memory".equals(normalizedScope) || "all".equals(normalizedScope)) {
            if (contains.isBlank()) {
                clearedMemory = processed.size();
                processed.clear();
            } else {
                Iterator<Map.Entry<String, Long>> iterator = processed.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Long> entry = iterator.next();
                    if (entry.getKey().toLowerCase(Locale.ROOT).contains(contains)) {
                        iterator.remove();
                        clearedMemory++;
                    }
                }
            }
        }
        if ("redis".equals(normalizedScope) || "all".equals(normalizedScope)) {
            if (redisTemplate != null && !redisDisabled) {
                try {
                    String pattern = contains.isBlank() ? redisKeyPrefix + "*" : redisKeyPrefix + "*" + contains + "*";
                    Set<String> keys = new HashSet<>();
                    ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
                    try (Cursor<String> cursor = redisTemplate.scan(options)) {
                        while (cursor.hasNext()) {
                            keys.add(cursor.next());
                        }
                    }
                    if (!keys.isEmpty()) {
                        Long deleted = redisTemplate.delete(keys);
                        clearedRedis = deleted == null ? 0 : deleted;
                    }
                } catch (RuntimeException e) {
                    if (warned.compareAndSet(false, true)) {
                        logger.warn("Redis 幂等键清理失败，已忽略。原因: {}", e.getMessage());
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", normalizedScope);
        result.put("keyContains", contains);
        result.put("clearedMemory", clearedMemory);
        result.put("clearedRedis", clearedRedis);
        result.put("inMemorySize", processed.size());
        result.put("redisAvailable", redisTemplate != null && !redisDisabled);
        return result;
    }
}
