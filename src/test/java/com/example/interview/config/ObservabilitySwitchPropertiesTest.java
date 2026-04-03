package com.example.interview.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilitySwitchPropertiesTest {

    @Test
    void shouldEnableRagQualityEvalByDefault() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        assertTrue(properties.isRagQualityEvalEnabled());
    }

    @Test
    void shouldUpdateRagQualityEvalSwitch() {
        ObservabilitySwitchProperties properties = new ObservabilitySwitchProperties();
        properties.setRagQualityEvalEnabled(false);
        assertFalse(properties.isRagQualityEvalEnabled());
    }
}
