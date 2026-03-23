package com.example.interview.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.MenuConfig;
import com.example.interview.entity.MenuConfigDO;
import com.example.interview.mapper.MenuConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
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
        return doList.stream().map(node -> {
            MenuConfig config = new MenuConfig();
            config.setId(node.getMenuCode());
            config.setTitle(node.getTitle());
            config.setDescription(node.getDescription());
            config.setIcon(node.getIcon());
            config.setUrl(node.getPath());
            config.setPosition(node.getPosition());
            config.setOrderIndex(node.getSortOrder() != null ? node.getSortOrder() : 0);
            config.setBeta(node.getIsBeta() != null ? node.getIsBeta() : false);
            return config;
        }).sorted(Comparator.comparingInt(MenuConfig::getOrderIndex)).collect(Collectors.toList());
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
}