package io.github.imzmq.interview.chat.infrastructure.milvus;

import java.util.List;

public interface ChatMemoryProjectionCursor extends AutoCloseable {

    List<ChatMemoryProjectionRow> nextPage();

    @Override
    void close();
}
