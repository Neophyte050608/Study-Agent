package io.github.imzmq.interview.menu.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.imzmq.interview.config.menu.MenuConfig;
import io.github.imzmq.interview.entity.menu.MenuConfigDO;
import io.github.imzmq.interview.mapper.menu.MenuConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单与扩展空间布局配置服务。
 */
@Service
public class MenuConfigService {

    private static final Logger logger = LoggerFactory.getLogger(MenuConfigService.class);
    private final MenuConfigMapper menuConfigMapper;

    public MenuConfigService(MenuConfigMapper menuConfigMapper) {
        this.menuConfigMapper = menuConfigMapper;
    }

    /**
     * 获取所有菜单配置（按 order 排序）
     */
    @Cacheable(value = "menuConfig", key = "'allMenus'")
    public List<MenuConfig> getAllMenus() {
        List<MenuConfigDO> doList = menuConfigMapper.selectList(Wrappers.emptyWrapper());
        List<MenuConfig> menus = doList.stream().map(node -> {
            MenuConfig config = new MenuConfig();
            config.setId(node.getMenuCode());
            config.setTitle(node.getTitle());
            config.setDescription(node.getDescription());
            config.setIcon(node.getIcon());
            config.setUrl(normalizeUrl(node.getPath()));
            config.setPosition(node.getPosition());
            config.setOrderIndex(node.getSortOrder() != null ? node.getSortOrder() : 0);
            config.setBeta(node.getIsBeta() != null ? node.getIsBeta() : false);
            return config;
        }).sorted(Comparator.comparingInt(MenuConfig::getOrderIndex)).collect(Collectors.toList());
        return mergeDefaults(menus);
    }

    /**
     * 批量更新菜单布局状态
     */
    @CacheEvict(value = "menuConfig", allEntries = true)
    public void updateLayout(List<MenuConfig> updatedMenus) {
        for (MenuConfig menuConfig : updatedMenus) {
            MenuConfigDO existing = menuConfigMapper.selectOne(
                    Wrappers.<MenuConfigDO>lambdaQuery().eq(MenuConfigDO::getMenuCode, menuConfig.getId())
            );
            if (existing != null) {
                existing.setTitle(menuConfig.getTitle());
                existing.setDescription(menuConfig.getDescription());
                existing.setIcon(menuConfig.getIcon());
                existing.setPath(menuConfig.getUrl());
                existing.setPosition(menuConfig.getPosition());
                existing.setSortOrder(menuConfig.getOrderIndex());
                existing.setIsBeta(menuConfig.isBeta());
                menuConfigMapper.updateById(existing);
            } else {
                MenuConfigDO newDO = new MenuConfigDO();
                newDO.setMenuCode(menuConfig.getId());
                newDO.setTitle(menuConfig.getTitle());
                newDO.setDescription(menuConfig.getDescription());
                newDO.setIcon(menuConfig.getIcon());
                newDO.setPath(menuConfig.getUrl());
                newDO.setPosition(menuConfig.getPosition());
                newDO.setSortOrder(menuConfig.getOrderIndex());
                newDO.setIsBeta(menuConfig.isBeta());
                menuConfigMapper.insert(newDO);
            }
        }
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "/interview";
        }
        return switch (url) {
            case "/", "/interview.html" -> "/interview";
            case "/knowledge", "/knowledge.html", "/notes.html" -> "/notes";
            case "/practice", "/practice.html", "/coding.html" -> "/coding";
            case "/profile.html" -> "/profile";
            case "/monitoring.html" -> "/monitoring";
            case "/ops.html" -> "/ops";
            case "/settings.html" -> "/settings";
            case "/workspace.html" -> "/workspace";
            case "/mcp.html" -> "/mcp";
            case "/intent-tree.html" -> "/intent-tree";
            default -> url;
        };
    }

    private List<MenuConfig> mergeDefaults(List<MenuConfig> menus) {
        Map<String, MenuConfig> merged = new LinkedHashMap<>();
        for (MenuConfig menu : menus) {
            merged.put(menu.getId(), menu);
        }
        List<MenuConfig> defaults = new ArrayList<>();
        defaults.add(new MenuConfig("DASHBOARD", "面试控制台", "开始一场新的模拟面试", "dashboard", "/interview", "SIDEBAR", 1, false));
        defaults.add(new MenuConfig("NOTES", "知识库管理", "同步与搜索你的个人笔记", "description", "/notes", "SIDEBAR", 2, false));
        defaults.add(new MenuConfig("CODING", "算法刷题", "针对薄弱点进行专项算法训练", "code", "/coding", "SIDEBAR", 3, false));
        defaults.add(new MenuConfig("PROFILE", "能力画像", "查看你的技术掌握度曲线", "analytics", "/profile", "SIDEBAR", 4, false));
        defaults.add(new MenuConfig("MONITORING", "系统监控", "模型路由、熔断状态与调用统计", "monitoring", "/monitoring", "SIDEBAR", 5, false));
        defaults.add(new MenuConfig("CHAT", "AI 助手", "与 AI 进行知识问答对话", "chat_bubble", "/chat", "SIDEBAR", 6, false));
        defaults.add(new MenuConfig("OPS", "运维中心", "查看 RAG 运行态与审计信息", "build", "/ops", "EXTENSION", 7, false));
        defaults.add(new MenuConfig("SETTINGS", "模型配置", "统一配置 Agent 的模型参数", "tune", "/settings", "EXTENSION", 8, false));
        defaults.add(new MenuConfig("MCP", "MCP 工具台", "查看能力并发起 MCP 调用", "hub", "/mcp", "EXTENSION", 9, false));
        defaults.add(new MenuConfig("INTENT_TREE", "意图树配置", "在线维护意图树阈值与策略", "account_tree", "/intent-tree", "EXTENSION", 10, false));
        defaults.add(new MenuConfig("WORKSPACE", "扩展空间", "管理菜单布局与扩展模块入口", "dashboard_customize", "/workspace", "EXTENSION", 11, false));
        for (MenuConfig fallback : defaults) {
            merged.putIfAbsent(fallback.getId(), fallback);
        }
        return merged.values().stream()
                .peek(item -> item.setUrl(normalizeUrl(item.getUrl())))
                .sorted(Comparator.comparingInt(MenuConfig::getOrderIndex))
                .collect(Collectors.toList());
    }
}






