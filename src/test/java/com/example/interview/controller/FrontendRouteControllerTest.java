package com.example.interview.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrontendRouteControllerTest {

    @Test
    void shouldRedirectLegacyMonitoring() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/monitoring", controller.redirectLegacyMonitoring());
    }

    @Test
    void shouldRedirectLegacyInterview() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/interview", controller.redirectLegacyInterview());
    }

    @Test
    void shouldRedirectLegacyKnowledge() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/notes", controller.redirectLegacyKnowledge());
    }

    @Test
    void shouldRedirectLegacyNotes() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/notes", controller.redirectLegacyNotes());
    }

    @Test
    void shouldRedirectLegacyPractice() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/coding", controller.redirectLegacyPractice());
    }

    @Test
    void shouldRedirectLegacyCoding() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/coding", controller.redirectLegacyCoding());
    }

    @Test
    void shouldRedirectLegacyProfile() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/profile", controller.redirectLegacyProfile());
    }

    @Test
    void shouldRedirectLegacyOps() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/ops", controller.redirectLegacyOps());
    }

    @Test
    void shouldRedirectLegacySettings() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/settings", controller.redirectLegacySettings());
    }

    @Test
    void shouldRedirectLegacyWorkspace() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/workspace", controller.redirectLegacyWorkspace());
    }

    @Test
    void shouldRedirectLegacyMcp() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/mcp", controller.redirectLegacyMcp());
    }

    @Test
    void shouldRedirectLegacyIntentTree() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("redirect:/intent-tree", controller.redirectLegacyIntentTree());
    }

    @Test
    void shouldForwardSpaRoutes() {
        FrontendRouteController controller = new FrontendRouteController();
        assertEquals("forward:/spa/index.html", controller.forwardToSpa());
    }
}
