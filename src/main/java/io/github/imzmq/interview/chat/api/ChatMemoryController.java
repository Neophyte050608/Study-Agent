package io.github.imzmq.interview.chat.api;

import io.github.imzmq.interview.chat.application.contract.ChatMemoryOperations;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/memories")
public class ChatMemoryController {

    private final ChatMemoryOperations memoryOperations;
    private final UserIdentityQuery userIdentityQuery;

    public ChatMemoryController(
            ChatMemoryOperations memoryOperations,
            UserIdentityQuery userIdentityQuery) {
        this.memoryOperations = memoryOperations;
        this.userIdentityQuery = userIdentityQuery;
    }

    @GetMapping
    public ResponseEntity<List<ChatMemoryResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long beforeId,
            HttpServletRequest request) {
        String userId = resolveUserIdentity(request);
        List<ChatMemoryResponse> response = memoryOperations
                .list(userId, status, limit, beforeId).stream()
                .map(ChatMemoryResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{memoryId}/evolution")
    public ResponseEntity<List<ChatMemoryRelationResponse>> evolution(
            @PathVariable long memoryId,
            HttpServletRequest request) {
        List<ChatMemoryRelationResponse> response = memoryOperations
                .evolution(resolveUserIdentity(request), memoryId).stream()
                .map(ChatMemoryRelationResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{memoryId}/resolve/replacement")
    public ResponseEntity<Void> confirmReplacement(
            @PathVariable long memoryId,
            @RequestParam long expectedVersion,
            HttpServletRequest request) {
        memoryOperations.confirmReplacement(
                resolveUserIdentity(request), memoryId, expectedVersion);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{memoryId}/resolve/coexistence")
    public ResponseEntity<Void> confirmCoexistence(
            @PathVariable long memoryId,
            @RequestParam long expectedVersion,
            HttpServletRequest request) {
        memoryOperations.confirmCoexistence(
                resolveUserIdentity(request), memoryId, expectedVersion);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(
            @PathVariable long memoryId,
            @RequestParam long expectedVersion,
            HttpServletRequest request) {
        memoryOperations.delete(resolveUserIdentity(request), memoryId, expectedVersion);
        return ResponseEntity.ok().build();
    }

    private String resolveUserIdentity(HttpServletRequest request) {
        String authenticatedPrincipal = request.getUserPrincipal() == null
                ? ""
                : request.getUserPrincipal().getName();
        return userIdentityQuery.resolveTrusted(
                authenticatedPrincipal,
                () -> request.getSession(true).getId());
    }
}
