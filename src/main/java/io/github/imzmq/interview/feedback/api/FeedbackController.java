package io.github.imzmq.interview.feedback.api;

import io.github.imzmq.interview.feedback.application.FeedbackApplicationService;
import io.github.imzmq.interview.feedback.domain.FeedbackEvent;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final FeedbackApplicationService feedbackApplicationService;
    private final UserIdentityResolver userIdentityResolver;

    public FeedbackController(FeedbackApplicationService feedbackApplicationService,
                              UserIdentityResolver userIdentityResolver) {
        this.feedbackApplicationService = feedbackApplicationService;
        this.userIdentityResolver = userIdentityResolver;
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> submitFeedback(@RequestBody Map<String, Object> payload,
                                                HttpServletRequest request) {
        String typeStr = asString(payload.get("type"));
        if (typeStr == null) {
            return ResponseEntity.badRequest().build();
        }

        FeedbackEvent.FeedbackType type;
        try {
            type = FeedbackEvent.FeedbackType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        String userId = userIdentityResolver.resolve(request);
        FeedbackEvent event = FeedbackEvent.fromRequest(
                asString(payload.get("traceId")),
                asString(payload.get("messageId")),
                userId,
                type,
                asString(payload.get("scene")),
                asString(payload.get("queryText"))
        );

        feedbackApplicationService.record(event);
        return ResponseEntity.noContent().build();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
