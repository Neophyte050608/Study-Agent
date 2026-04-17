package io.github.imzmq.interview.im.application.runtime;

import io.github.imzmq.interview.im.domain.UnifiedReply;
import io.github.imzmq.interview.im.application.service.QqReplyAdapter;
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



