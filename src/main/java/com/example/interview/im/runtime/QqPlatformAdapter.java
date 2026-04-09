package com.example.interview.im.runtime;

import com.example.interview.im.model.UnifiedReply;
import com.example.interview.im.service.QqReplyAdapter;
import org.springframework.stereotype.Component;

@Component
public class QqPlatformAdapter implements ImPlatformAdapter {

    private final QqReplyAdapter qqReplyAdapter;

    public QqPlatformAdapter(QqReplyAdapter qqReplyAdapter) {
        this.qqReplyAdapter = qqReplyAdapter;
    }

    @Override
    public boolean supports(String platform) {
        return "qq".equalsIgnoreCase(platform);
    }

    @Override
    public void sendReply(UnifiedReply reply) {
        qqReplyAdapter.sendReply(reply);
    }
}
