package com.example.interview.session;

import com.example.interview.core.InterviewSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于内存的面试会话仓库。
 * 
 * 职责：
 * 1. 内存存储：利用 LinkedHashMap 提供基于 LRU (最近最少使用) 的会话存储，默认保留最近 10 次记录。
 * 2. 持久化：在系统启动 (@PostConstruct) 时从本地 JSON 加载，在更新或关闭 (@PreDestroy) 时持久化到本地，防止重启丢失数据。
 */
@Component
public class InMemorySessionRepository implements SessionRepository {

    private static final Logger logger = LoggerFactory.getLogger(InMemorySessionRepository.class);
    private static final String STORE_FILE = "interview_sessions.json";
    private final ObjectMapper objectMapper;

    // 保留最近 10 次面试记录
    private static final int MAX_SESSIONS = 10;
    
    // 使用支持 LRU 或基于插入顺序淘汰的 LinkedHashMap
    private Map<String, InterviewSession> sessions = new LinkedHashMap<String, InterviewSession>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, InterviewSession> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    public InMemorySessionRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadFromFile() {
        File file = new File(STORE_FILE);
        if (file.exists()) {
            try {
                Map<String, InterviewSession> loaded = objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, InterviewSession>>() {});
                if (loaded != null) {
                    sessions.putAll(loaded);
                    logger.info("Loaded {} interview sessions from local file.", sessions.size());
                }
            } catch (IOException e) {
                logger.error("Failed to load interview sessions from file", e);
            }
        }
    }

    @PreDestroy
    public void saveToFile() {
        flushToDisk();
    }

    private void flushToDisk() {
        try {
            objectMapper.writeValue(new File(STORE_FILE), sessions);
        } catch (IOException e) {
            logger.error("Failed to save interview sessions to file", e);
        }
    }

    @Override
    public synchronized InterviewSession save(InterviewSession session) {
        sessions.put(session.getId(), session);
        flushToDisk(); // 每次保存时异步/同步写入本地文件
        return session;
    }

    @Override
    public synchronized Optional<InterviewSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
