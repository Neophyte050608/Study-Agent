package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.im.domain.UnifiedReply;
import io.github.imzmq.interview.im.application.service.FeishuReplyAdapter;
import org.springframework.stereotype.Component;

@Component
public class FeishuPlatformAdapter implements ImPlatformAdapter {

    private final FeishuReplyAdapter feishuReplyAdapter;

    public FeishuPlatformAdapter(FeishuReplyAdapter feishuReplyAdapter) {
        this.feishuReplyAdapter = feishuReplyAdapter;
    }

    @Override
    public boolean supports(String platform) {
        return "feishu".equalsIgnoreCase(platform);
    }

    @Override
    public void sendReply(UnifiedReply reply) {
        feishuReplyAdapter.sendReply(reply);
    }
}



