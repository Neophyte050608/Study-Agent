package com.example.interview.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.core.InterviewSession;
import com.example.interview.entity.AgentConfigDO;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.entity.InterviewSessionDO;
import com.example.interview.entity.LearningEventDO;
import com.example.interview.entity.LearningProfileDO;
import com.example.interview.entity.LexicalIndexDO;
import com.example.interview.entity.MenuConfigDO;
import com.example.interview.entity.SyncIndexDO;
import com.example.interview.mapper.AgentConfigMapper;
import com.example.interview.mapper.IntentNodeMapper;
import com.example.interview.mapper.InterviewSessionMapper;
import com.example.interview.mapper.LearningEventMapper;
import com.example.interview.mapper.LearningProfileMapper;
import com.example.interview.mapper.LexicalIndexMapper;
import com.example.interview.mapper.MenuConfigMapper;
import com.example.interview.mapper.SyncIndexMapper;
import com.example.interview.service.IngestionService;
import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 历史数据迁移启动器。
 * 职责：在系统启动时，如果数据库表为空，则尝试从本地的 JSON 配置文件中读取历史数据并写入 MySQL，
 * 从而实现无缝平滑迁移。
 */
@Component
public class DataMigrationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final IntentNodeMapper intentNodeMapper;
    private final AgentConfigMapper agentConfigMapper;
    private final MenuConfigMapper menuConfigMapper;
    private final InterviewSessionMapper interviewSessionMapper;
    private final LearningProfileMapper learningProfileMapper;
    private final LearningEventMapper learningEventMapper;
    private final LexicalIndexMapper lexicalIndexMapper;
    private final SyncIndexMapper syncIndexMapper;
    private final ObjectMapper objectMapper;

    public DataMigrationRunner(
            IntentNodeMapper intentNodeMapper,
            AgentConfigMapper agentConfigMapper,
            MenuConfigMapper menuConfigMapper,
            InterviewSessionMapper interviewSessionMapper,
            LearningProfileMapper learningProfileMapper,
            LearningEventMapper learningEventMapper,
            LexicalIndexMapper lexicalIndexMapper,
            SyncIndexMapper syncIndexMapper,
            ObjectMapper objectMapper) {
        this.intentNodeMapper = intentNodeMapper;
        this.agentConfigMapper = agentConfigMapper;
        this.menuConfigMapper = menuConfigMapper;
        this.interviewSessionMapper = interviewSessionMapper;
        this.learningProfileMapper = learningProfileMapper;
        this.learningEventMapper = learningEventMapper;
        this.lexicalIndexMapper = lexicalIndexMapper;
        this.syncIndexMapper = syncIndexMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("开始检查是否需要进行历史数据迁移...");
        migrateIntentTree();
        migrateAgentConfig();
        migrateMenuConfig();
        migrateInterviewSession();
        migrateLearningProfile();
        migrateLexicalIndex();
        migrateSyncIndex();
        logger.info("历史数据迁移检查完毕。");
    }

    private void migrateLearningProfile() {
        if (learningProfileMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("learning_profiles_v2.json");
            if (file.exists()) {
                try {
                    Map<String, LearningProfileAgent.UserProfileState> loaded = objectMapper.readValue(
                            file, new TypeReference<Map<String, LearningProfileAgent.UserProfileState>>() {});
                    if (loaded != null) {
                        for (Map.Entry<String, LearningProfileAgent.UserProfileState> entry : loaded.entrySet()) {
                            String userId = entry.getKey();
                            LearningProfileAgent.UserProfileState state = entry.getValue();

                            LearningProfileDO profileDO = new LearningProfileDO();
                            profileDO.setUserId(userId);
                            profileDO.setTotalEvents(state.totalEvents);
                            Map<String, Object> metrics = new LinkedHashMap<>();
                            if (state.topicMetrics != null) {
                                state.topicMetrics.forEach(metrics::put);
                            }
                            profileDO.setTopicMetrics(metrics);
                            profileDO.setReliabilityScore(0.0);
                            learningProfileMapper.insert(profileDO);

                            if (state.events != null) {
                                for (LearningEvent event : state.events) {
                                    LearningEventDO eventDO = new LearningEventDO();
                                    eventDO.setEventId(event.eventId());
                                    eventDO.setUserId(userId);
                                    eventDO.setSource(event.source() != null ? event.source().name() : "INTERVIEW");
                                    eventDO.setTopic(event.topic());
                                    eventDO.setScore(event.score());
                                    eventDO.setWeakPoints(event.weakPoints());
                                    eventDO.setFamiliarPoints(event.familiarPoints());
                                    eventDO.setEvidence(event.evidence());
                                    eventDO.setTimestamp(event.timestamp() != null ? 
                                            LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()) : 
                                            LocalDateTime.now());
                                    learningEventMapper.insert(eventDO);
                                }
                            }
                        }
                        logger.info("成功迁移学习画像数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移学习画像数据失败", e);
                }
            }
        }
    }

    private void migrateLexicalIndex() {
        if (lexicalIndexMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("lexical_index.json");
            if (file.exists()) {
                try {
                    Map<String, Map<String, Object>> loaded = objectMapper.readValue(
                            file, new TypeReference<Map<String, Map<String, Object>>>() {});
                    if (loaded != null) {
                        for (Map.Entry<String, Map<String, Object>> entry : loaded.entrySet()) {
                            Map<String, Object> record = entry.getValue();
                            LexicalIndexDO indexDO = new LexicalIndexDO();
                            indexDO.setDocId(entry.getKey());
                            indexDO.setText((String) record.get("text"));
                            indexDO.setFilePath((String) record.get("filePath"));
                            indexDO.setKnowledgeTags((String) record.get("knowledgeTags"));
                            indexDO.setSourceType((String) record.get("sourceType"));
                            indexDO.setCreatedAt(LocalDateTime.now());
                            lexicalIndexMapper.insert(indexDO);
                        }
                        logger.info("成功迁移词法索引数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移词法索引数据失败", e);
                }
            }
        }
    }

    private void migrateSyncIndex() {
        if (syncIndexMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("sync_index.json");
            if (file.exists()) {
                try {
                    Map<String, OldFileMetadata> loaded = objectMapper.readValue(
                            file, new TypeReference<ConcurrentHashMap<String, OldFileMetadata>>() {});
                    if (loaded != null) {
                        for (Map.Entry<String, OldFileMetadata> entry : loaded.entrySet()) {
                            SyncIndexDO indexDO = new SyncIndexDO();
                            indexDO.setFilePath(entry.getKey());
                            indexDO.setFileHash(entry.getValue().hash);
                            indexDO.setDocIds(entry.getValue().docIds);
                            indexDO.setCreatedAt(LocalDateTime.now());
                            syncIndexMapper.insert(indexDO);
                        }
                        logger.info("成功迁移同步索引数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移同步索引数据失败", e);
                }
            }
        }
    }

    public static class OldFileMetadata {
        public String hash;
        public List<String> docIds;
    }

    private void migrateIntentTree() {
        if (intentNodeMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("intent_tree_configs.json");
            if (file.exists()) {
                try {
                    IntentTreeProperties props = objectMapper.readValue(file, IntentTreeProperties.class);
                    if (props.getLeafIntents() != null) {
                        for (IntentTreeProperties.LeafIntentConfig config : props.getLeafIntents()) {
                            IntentNodeDO node = new IntentNodeDO();
                            node.setIntentCode(config.getIntentId());
                            node.setName(config.getName());
                            node.setDescription(config.getDescription());
                            node.setTaskType(config.getTaskType());
                            node.setExamples(config.getExamples());
                            node.setSlotHints(config.getSlotHints());
                            node.setEnabled(true);
                            node.setLevel(2); // 默认叶子节点
                            intentNodeMapper.insert(node);
                        }
                        logger.info("成功迁移意图树数据 {} 条", props.getLeafIntents().size());
                    }
                } catch (Exception e) {
                    logger.error("迁移意图树数据失败", e);
                }
            }
        }
    }

    private void migrateAgentConfig() {
        if (agentConfigMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("agent_configs.json");
            if (file.exists()) {
                try {
                    Map<String, AgentConfig> loaded = objectMapper.readValue(file, new TypeReference<Map<String, AgentConfig>>() {});
                    if (loaded != null) {
                        loaded.forEach((name, config) -> {
                            AgentConfigDO node = new AgentConfigDO();
                            node.setAgentName(name);
                            node.setProvider(config.getProvider());
                            node.setModelName(config.getModelName());
                            node.setApiKey(config.getApiKey());
                            node.setTemperature(config.getTemperature());
                            node.setEnabled(config.isEnabled());
                            agentConfigMapper.insert(node);
                        });
                        logger.info("成功迁移 Agent 配置数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移 Agent 配置数据失败", e);
                }
            }
        }
    }

    private void migrateMenuConfig() {
        if (menuConfigMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("menu_configs.json");
            if (file.exists()) {
                try {
                    List<MenuConfig> loaded = objectMapper.readValue(file, new TypeReference<List<MenuConfig>>() {});
                    if (loaded != null) {
                        for (MenuConfig config : loaded) {
                            MenuConfigDO node = new MenuConfigDO();
                            node.setMenuCode(config.getId());
                            node.setTitle(config.getTitle());
                            node.setDescription(config.getDescription());
                            node.setPath(config.getUrl());
                            node.setIcon(config.getIcon());
                            node.setPosition(config.getPosition());
                            node.setSortOrder(config.getOrderIndex());
                            node.setIsBeta(config.isBeta());
                            menuConfigMapper.insert(node);
                        }
                        logger.info("成功迁移菜单配置数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移菜单配置数据失败", e);
                }
            }
        }
    }

    private void migrateInterviewSession() {
        if (interviewSessionMapper.selectCount(Wrappers.emptyWrapper()) == 0) {
            File file = new File("interview_sessions.json");
            if (file.exists()) {
                try {
                    Map<String, InterviewSession> loaded = objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, InterviewSession>>() {});
                    if (loaded != null) {
                        loaded.forEach((id, session) -> {
                            InterviewSessionDO node = new InterviewSessionDO();
                            node.setSessionId(session.getId());
                            node.setUserId(session.getUserId() != null ? session.getUserId() : "local-user");
                            node.setCurrentStage(session.getCurrentStage() != null ? session.getCurrentStage().name() : "UNKNOWN");
                            node.setContextData(session);
                            interviewSessionMapper.insert(node);
                        });
                        logger.info("成功迁移面试会话数据 {} 条", loaded.size());
                    }
                } catch (Exception e) {
                    logger.error("迁移面试会话数据失败", e);
                }
            }
        }
    }
}
