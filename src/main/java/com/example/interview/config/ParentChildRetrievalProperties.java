package com.example.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.rag.parent-child")
public class ParentChildRetrievalProperties {

    private boolean enabled = true;
    private int childTargetSize = 220;
    private int childOverlap = 40;
    private int parentMaxSize = 1200;
    private int hydrateParentTopN = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChildTargetSize() {
        return childTargetSize;
    }

    public void setChildTargetSize(int childTargetSize) {
        this.childTargetSize = childTargetSize;
    }

    public int getChildOverlap() {
        return childOverlap;
    }

    public void setChildOverlap(int childOverlap) {
        this.childOverlap = childOverlap;
    }

    public int getParentMaxSize() {
        return parentMaxSize;
    }

    public void setParentMaxSize(int parentMaxSize) {
        this.parentMaxSize = parentMaxSize;
    }

    public int getHydrateParentTopN() {
        return hydrateParentTopN;
    }

    public void setHydrateParentTopN(int hydrateParentTopN) {
        this.hydrateParentTopN = hydrateParentTopN;
    }
}
