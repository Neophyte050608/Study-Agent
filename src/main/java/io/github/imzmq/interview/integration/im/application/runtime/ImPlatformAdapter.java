package io.github.imzmq.interview.integration.im.application.runtime;

import io.github.imzmq.interview.integration.im.domain.UnifiedReply;

public interface ImPlatformAdapter {

    boolean supports(String platform);

    void sendReply(UnifiedReply reply);
}
