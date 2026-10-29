package io.github.imzmq.interview.menu.application;

import io.github.imzmq.interview.config.menu.MenuConfig;
import io.github.imzmq.interview.menu.infrastructure.persistence.MenuConfigDO;
import io.github.imzmq.interview.menu.infrastructure.persistence.MenuConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuConfigServiceTest {

    private MenuConfigMapper menuConfigMapper;
    private MenuConfigService service;

    @BeforeEach
    void setUp() {
        menuConfigMapper = mock(MenuConfigMapper.class);
        service = new MenuConfigService(menuConfigMapper);
    }

    @Test
    void defaultsExposeChatWithoutLegacyInterviewOrCodingEntries() {
        when(menuConfigMapper.selectList(any())).thenReturn(List.of());

        List<MenuConfig> menus = service.getAllMenus();

        assertThat(menus).extracting(MenuConfig::getId)
                .contains("CHAT")
                .doesNotContain("DASHBOARD", "CODING");
        assertThat(menus.stream().filter(menu -> "CHAT".equals(menu.getId())).findFirst().orElseThrow().getUrl())
                .isEqualTo("/chat");
    }

    @Test
    void blankPersistedPathDefaultsToChat() {
        MenuConfigDO row = menu("CUSTOM", "");
        when(menuConfigMapper.selectList(any())).thenReturn(List.of(row));

        assertThat(service.getAllMenus().stream()
                .filter(menu -> "CUSTOM".equals(menu.getId()))
                .findFirst().orElseThrow().getUrl()).isEqualTo("/chat");
    }

    private MenuConfigDO menu(String code, String path) {
        MenuConfigDO row = new MenuConfigDO();
        row.setMenuCode(code);
        row.setTitle(code);
        row.setPath(path);
        row.setPosition("SIDEBAR");
        row.setSortOrder(100);
        row.setIsBeta(false);
        return row;
    }
}
