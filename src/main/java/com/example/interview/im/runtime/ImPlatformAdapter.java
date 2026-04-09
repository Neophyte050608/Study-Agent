package com.example.interview.im.runtime;

import com.example.interview.im.model.UnifiedReply;

public interface ImPlatformAdapter {

    boolean supports(String platform);

    void sendReply(UnifiedReply reply);
}
