package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.interview.application.InterviewStreamingService;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/interview/stream")
public class InterviewStreamController {
    private final InterviewStreamingService interviewStreamingService;
    private final UserIdentityResolver userIdentityResolver;

    public InterviewStreamController(
            InterviewStreamingService interviewStreamingService,
            UserIdentityResolver userIdentityResolver
    ) {
        this.interviewStreamingService = interviewStreamingService;
        this.userIdentityResolver = userIdentityResolver;
    }

    @PostMapping(value = "/start", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamStart(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return interviewStreamingService.streamStart(payload, userId);
    }

    @PostMapping(value = "/answer", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamAnswer(@RequestBody Map<String, Object> payload) {
        return interviewStreamingService.streamAnswer(payload);
    }

    @PostMapping(value = "/report", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamReport(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return interviewStreamingService.streamReport(payload, userId);
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, String> payload) {
        String streamTaskId = payload.get("streamTaskId");
        if (streamTaskId == null || streamTaskId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "streamTaskId 不能为空"));
        }
        boolean stopped = interviewStreamingService.stopTask(streamTaskId.trim());
        return ResponseEntity.ok(Map.of(
                "streamTaskId", streamTaskId.trim(),
                "stopped", stopped
        ));
    }
}






