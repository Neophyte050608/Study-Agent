package com.example.interview.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentSplitter {

    // Using TokenTextSplitter for better LLM context management
    private final TokenTextSplitter tokenTextSplitter;

    public DocumentSplitter() {
        // Default: 800 tokens per chunk, 350 tokens overlap, true (keep separators), etc.
        // Adjust these values based on the embedding model's context window (e.g., text-embedding-ada-002 is 8191)
        // But for retrieval, smaller chunks (e.g., 500-1000) are often better.
        this.tokenTextSplitter = new TokenTextSplitter(800, 350, 5, 10000, true);
    }

    public List<Document> split(List<Document> documents) {
        return this.tokenTextSplitter.apply(documents);
    }
}
