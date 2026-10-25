package io.github.imzmq.interview.search.api;

import io.github.imzmq.interview.search.application.dto.AutocompleteItem;
import io.github.imzmq.interview.identity.application.contract.UserIdentityQuery;
import io.github.imzmq.interview.search.application.AutocompleteService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/autocomplete")
public class AutocompleteController {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_LIMIT = 50;

    private final AutocompleteService autocompleteService;
    private final UserIdentityQuery userIdentityQuery;

    public AutocompleteController(AutocompleteService autocompleteService,
                                  UserIdentityQuery userIdentityQuery) {
        this.autocompleteService = autocompleteService;
        this.userIdentityQuery = userIdentityQuery;
    }

    @GetMapping
    public ResponseEntity<List<AutocompleteItem>> suggest(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest request) {
        if (query == null || query.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        int clampedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        String userId = resolveUserIdentity(request);
        List<AutocompleteItem> results = autocompleteService.search(query.trim(), userId, clampedLimit);
        return ResponseEntity.ok(results);
    }

    @PostMapping("/click")
    public ResponseEntity<Void> recordClick(@RequestBody Map<String, Long> body) {
        Long entryId = body.get("entryId");
        if (entryId != null) {
            autocompleteService.recordClick(entryId);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/dict/refresh")
    public ResponseEntity<Void> refreshDict(HttpServletRequest request) {
        String userId = resolveUserIdentity(request);
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(403).build();
        }
        autocompleteService.refreshTree();
        return ResponseEntity.ok().build();
    }

    private String resolveUserIdentity(jakarta.servlet.http.HttpServletRequest request) {
        return userIdentityQuery.resolve(
                request.getHeader("Authorization"),
                request.getHeader("X-User-Id"),
                () -> request.getSession(true).getId()
        );
    }
}







