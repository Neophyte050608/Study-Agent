package com.example.interview.im.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ImConversationStore {

    private final StringRedisTemplate redisTemplate;

    public ImConversationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    private static final String HISTORY_PREFIX = "im:session:history:";
    private static final String ACTIVE_SESSION_PREFIX = "im:session:active:";
    private static final int MAX_HISTORY = 10; // keep last 5 rounds (10 messages: 5 user + 5 ai)
    private static final long SESSION_TTL_HOURS = 24;

    public void addMessage(String sessionId, String role, String content) {
        String key = HISTORY_PREFIX + sessionId;
        String formattedMessage = role + ": " + content;
        
        redisTemplate.opsForList().rightPush(key, formattedMessage);
        
        // keep only the last MAX_HISTORY messages
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY) {
            redisTemplate.opsForList().trim(key, size - MAX_HISTORY, -1);
        }
        
        // update TTL
        redisTemplate.expire(key, SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    public void setActiveSession(String sessionId, String interviewSessionId) {
        String key = ACTIVE_SESSION_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, interviewSessionId, SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    public String getActiveSession(String sessionId) {
        String key = ACTIVE_SESSION_PREFIX + sessionId;
        return redisTemplate.opsForValue().get(key);
    }

    public String getHistoryContext(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        List<String> messages = redisTemplate.opsForList().range(key, 0, -1);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream().collect(Collectors.joining("\n"));
    }

    public void clearSession(String sessionId) {
        String key = HISTORY_PREFIX + sessionId;
        redisTemplate.delete(key);
        redisTemplate.delete(ACTIVE_SESSION_PREFIX + sessionId);
    }
}
