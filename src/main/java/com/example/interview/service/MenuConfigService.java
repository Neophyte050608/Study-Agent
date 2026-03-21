package com.example.interview.service;

import com.example.interview.config.MenuConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 菜单与扩展空间布局配置服务。
 */
@Service
public class MenuConfigService {

    private static final Logger logger = LoggerFactory.getLogger(MenuConfigService.class);
    private static final String CONFIG_FILE = "menu_configs.json";
    private final ObjectMapper objectMapper;

    // 内存缓存
    private List<MenuConfig> menus = new ArrayList<>();

    public MenuConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    private void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try {
                List<MenuConfig> loaded = objectMapper.readValue(file, new TypeReference<List<MenuConfig>>() {});
                if (loaded != null && !loaded.isEmpty()) {
                    menus.addAll(loaded);
                    return;
                }
            } catch (IOException e) {
                logger.error("Failed to load menu configs from {}", CONFIG_FILE, e);
            }
        }
        
        // 默认初始化数据
        menus.add(new MenuConfig("interview", "面试练习", "沉浸式 AI 模拟面试与深度复盘分析", "exercise", "interview.html", "SIDEBAR", 1, false));
        menus.add(new MenuConfig("knowledge", "知识库管理", "多源数据摄入与个人简历资产管理", "database", "knowledge.html", "SIDEBAR", 2, false));
        menus.add(new MenuConfig("profile", "学习画像", "基于 AI 洞察的技能成长轨迹与建议", "analytics", "profile.html", "SIDEBAR", 3, true));
        menus.add(new MenuConfig("mcp", "MCP 工具台", "Model Context Protocol 协议调试工作台", "terminal", "mcp.html", "SIDEBAR", 4, false));
        menus.add(new MenuConfig("ops", "观测与运维", "RAG 链路追踪与系统幂等状态监控", "monitoring", "ops.html", "EXTENSION", 5, true));
        menus.add(new MenuConfig("settings", "动态 Agent 设置", "多模型智能代理参数与供应商配置", "settings", "settings.html", "EXTENSION", 6, true));
        
        saveConfig();
    }

    private synchronized void saveConfig() {
        try {
            objectMapper.writeValue(new File(CONFIG_FILE), menus);
        } catch (IOException e) {
            logger.error("Failed to save menu configs to {}", CONFIG_FILE, e);
        }
    }

    /**
     * 获取所有菜单配置（按 order 排序）
     */
    public List<MenuConfig> getAllMenus() {
        menus.sort(Comparator.comparingInt(MenuConfig::getOrderIndex));
        return menus;
    }

    /**
     * 批量更新菜单布局状态
     */
    public void updateLayout(List<MenuConfig> updatedMenus) {
        this.menus = new ArrayList<>(updatedMenus);
        saveConfig();
    }
}