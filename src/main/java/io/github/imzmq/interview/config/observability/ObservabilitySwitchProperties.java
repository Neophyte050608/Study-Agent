package io.github.imzmq.interview.config.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可观测与评测开关配置。
 */
@Component
@ConfigurationProperties(prefix = "app.observability")
public class ObservabilitySwitchProperties {

    private boolean ragTraceEnabled = true;
    private boolean retrievalEvalEnabled = true;
    private boolean ragQualityEvalEnabled = true;

    public boolean isRagTraceEnabled() {
        return ragTraceEnabled;
    }

    public void setRagTraceEnabled(boolean ragTraceEnabled) {
        this.ragTraceEnabled = ragTraceEnabled;
    }

    public boolean isRetrievalEvalEnabled() {
        return retrievalEvalEnabled;
    }

    public void setRetrievalEvalEnabled(boolean retrievalEvalEnabled) {
        this.retrievalEvalEnabled = retrievalEvalEnabled;
    }

    public boolean isRagQualityEvalEnabled() {
        return ragQualityEvalEnabled;
    }

    public void setRagQualityEvalEnabled(boolean ragQualityEvalEnabled) {
        this.ragQualityEvalEnabled = ragQualityEvalEnabled;
    }
}

