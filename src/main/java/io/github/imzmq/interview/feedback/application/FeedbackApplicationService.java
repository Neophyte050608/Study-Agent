package io.github.imzmq.interview.feedback.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.entity.knowledge.RagFeedbackDO;
import io.github.imzmq.interview.feedback.domain.FeedbackEvent;
import io.github.imzmq.interview.mapper.knowledge.RagFeedbackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FeedbackApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackApplicationService.class);

    private final RagFeedbackMapper ragFeedbackMapper;

    public FeedbackApplicationService(RagFeedbackMapper ragFeedbackMapper) {
        this.ragFeedbackMapper = ragFeedbackMapper;
    }

    public void record(FeedbackEvent event) {
        RagFeedbackDO entity = new RagFeedbackDO();
        entity.setFeedbackId(event.feedbackId());
        entity.setTraceId(event.traceId());
        entity.setMessageId(event.messageId());
        entity.setUserId(event.userId());
        entity.setFeedbackType(event.type().name());
        entity.setScene(event.scene());
        entity.setQueryText(event.queryText());
        entity.setCreatedAt(LocalDateTime.now());

        try {
            ragFeedbackMapper.insert(entity);
        } catch (Exception e) {
            logger.warn("Failed to persist feedback feedbackId={}: {}", event.feedbackId(), e.getMessage());
        }
    }

    public long countByType(LocalDateTime from, LocalDateTime to, String feedbackType) {
        return ragFeedbackMapper.selectCount(new LambdaQueryWrapper<RagFeedbackDO>()
                .eq(RagFeedbackDO::getFeedbackType, feedbackType)
                .ge(RagFeedbackDO::getCreatedAt, from)
                .lt(RagFeedbackDO::getCreatedAt, to));
    }
}
