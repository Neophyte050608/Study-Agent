package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.im.domain.UnifiedReply;

public interface ImPlatformAdapter {

    boolean supports(String platform);

    void sendReply(UnifiedReply reply);
}



