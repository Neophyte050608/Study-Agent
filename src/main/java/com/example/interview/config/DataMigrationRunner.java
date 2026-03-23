package com.example.interview.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.core.InterviewSession;
import com.example.interview.entity.AgentConfigDO;
import com.example.interview.entity.IntentNodeDO;
import com.example.interview.entity.InterviewSessionDO;
import com.example.interview.entity.MenuConfigDO;
import com.example.interview.mapper.AgentConfigMapper;
import com.example.interview.mapper.IntentNodeMapper;
import com.example.interview.mapper.InterviewSessionMapper;
import com.example.interview.mapper.MenuConfigMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    public DataMigrationRunner(
            IntentNodeMapper intentNodeMapper,
            AgentConfigMapper agentConfigMapper,
            MenuConfigMapper menuConfigMapper,
            InterviewSessionMapper interviewSessionMapper,
            ObjectMapper objectMapper) {
        this.intentNodeMapper = intentNodeMapper;
        this.agentConfigMapper = agentConfigMapper;
        this.menuConfigMapper = menuConfigMapper;
        this.interviewSessionMapper = interviewSessionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("开始检查是否需要进行历史数据迁移...");
        migrateIntentTree();
        migrateAgentConfig();
        migrateMenuConfig();
        migrateInterviewSession();
        logger.info("历史数据迁移检查完毕。");
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
