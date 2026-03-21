package com.example.interview.config;

/**
 * 动态菜单配置实体。
 * 用于管理前端左侧边栏和扩展空间卡片的显示位置与顺序。
 */
public class MenuConfig {
    
    private String id;
    private String title;
    private String description;
    private String icon;
    private String url;
    /** SIDEBAR (显示在左侧) 或 EXTENSION (收纳在扩展空间) */
    private String position; 
    private int orderIndex;
    private boolean isBeta;

    public MenuConfig() {}

    public MenuConfig(String id, String title, String description, String icon, String url, String position, int orderIndex, boolean isBeta) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.icon = icon;
        this.url = url;
        this.position = position;
        this.orderIndex = orderIndex;
        this.isBeta = isBeta;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    
    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }

    public boolean isBeta() { return isBeta; }
    public void setBeta(boolean beta) { isBeta = beta; }
}