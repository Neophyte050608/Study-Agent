package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.interview.entity.ChatSessionDO;
import com.example.interview.entity.UserChatMemoryDO;
import com.example.interview.mapper.ChatSessionMapper;
import com.example.interview.mapper.UserChatMemoryMapper;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AutoDreamService {
    private static final Logger log = LoggerFactory.getLogger(AutoDreamService.class);

    private final UserChatMemoryMapper memoryMapper;
    private final ChatSessionMapper sessionMapper;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;

    @Value("${app.dream.interval-hours:24}")
    private int intervalHours;

    @Value("${app.dream.recent-sessions-count:10}")
    private int recentSessionsCount;

    public AutoDreamService(UserChatMemoryMapper memoryMapper,
                            ChatSessionMapper sessionMapper,
                            RoutingChatService routingChatService,
                            PromptManager promptManager) {
        this.memoryMapper = memoryMapper;
        this.sessionMapper = sessionMapper;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
    }

    @Scheduled(fixedDelayString = "#{${app.dream.interval-hours:24} * 3600000}", initialDelay = 300000)
    public void executeDream() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(intervalHours);
        List<UserChatMemoryDO> memories = memoryMapper.selectList(
                new LambdaQueryWrapper<UserChatMemoryDO>()
                        .and(w -> w.isNull(UserChatMemoryDO::getLastDreamAt)
                                   .or()
                                   .lt(UserChatMemoryDO::getLastDreamAt, threshold))
        );
        if (memories == null || memories.isEmpty()) {
            return;
        }

        for (UserChatMemoryDO memory : memories) {
            try {
                String currentMemory = memory.getMemoryText();
                if (currentMemory == null || currentMemory.length() < 20) {
                    continue;
                }

                List<ChatSessionDO> sessions = sessionMapper.selectList(
                        new LambdaQueryWrapper<ChatSessionDO>()
                                .eq(ChatSessionDO::getUserId, memory.getUserId())
                                .isNotNull(ChatSessionDO::getContextSummary)
                                .orderByDesc(ChatSessionDO::getUpdatedAt)
                                .last("LIMIT " + recentSessionsCount)
                );
                String recentSummaries = sessions.stream()
                        .map(ChatSessionDO::getContextSummary)
                        .filter(s -> s != null && !s.isBlank())
                        .collect(Collectors.joining("\n\n---\n\n"));

                Map<String, Object> vars = new HashMap<>();
                vars.put("currentMemory", currentMemory);
                vars.put("recentSummaries", recentSummaries);
                String prompt = promptManager.render("auto-dream", vars);
                String result = routingChatService.call(prompt, ModelRouteType.THINKING, "记忆整理");

                memoryMapper.update(null,
                        new LambdaUpdateWrapper<UserChatMemoryDO>()
                                .eq(UserChatMemoryDO::getId, memory.getId())
                                .set(UserChatMemoryDO::getMemoryText, result)
                                .set(UserChatMemoryDO::getLastDreamAt, LocalDateTime.now())
                );

                log.info("记忆整理完成: userId={}, intervalHours={}", memory.getUserId(), intervalHours);
            } catch (Exception e) {
                log.error("记忆整理失败: userId={}", memory.getUserId(), e);
            }
        }
    }
}
