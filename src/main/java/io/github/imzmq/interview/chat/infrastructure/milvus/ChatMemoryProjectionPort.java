package io.github.imzmq.interview.chat.infrastructure.milvus;

import java.time.Duration;
import java.util.List;

public interface ChatMemoryProjectionPort {

    void upsert(ChatMemoryProjection projection, Duration timeout);

    List<ChatMemoryVectorCandidate> search(
            String userId, String query, int limit, Duration timeout);

    List<ChatMemoryProjectionRow> findByProjectionIds(
            List<String> projectionIds, Duration timeout);

    ChatMemoryProjectionCursor openCursor(int pageSize, Duration timeout);

    void deleteVersion(String userId, Long memoryId, Long version, Duration timeout);

    void deleteOtherVersions(String userId, Long memoryId, Long currentVersion, Duration timeout);

    void deleteMemory(String userId, Long memoryId, Duration timeout);
}
