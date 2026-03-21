package com.example.interview.controller;

import com.example.interview.config.MenuConfig;
import com.example.interview.service.MenuConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/menu")
public class MenuController {

    private final MenuConfigService menuConfigService;

    public MenuController(MenuConfigService menuConfigService) {
        this.menuConfigService = menuConfigService;
    }

    @GetMapping
    public List<MenuConfig> getMenus() {
        return menuConfigService.getAllMenus();
    }

    @PostMapping("/layout")
    public Map<String, String> updateLayout(@RequestBody List<MenuConfig> menus) {
        menuConfigService.updateLayout(menus);
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        return response;
    }
}