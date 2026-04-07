package com.example.interview.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地笔记读取服务。
 *
 * <p>职责是将索引中的相对 filePath 解析到 vaultRoot 下，并读取笔记正文。</p>
 */
@Service
public class LocalNoteResolverService {

    public ResolvedNote resolve(KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                KnowledgeMapService.KnowledgeNode node) {
        if (snapshot == null || node == null) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_PARSE_FAILED,
                    "Snapshot or node is missing"
            );
        }
        if (snapshot.vaultRoot() == null || snapshot.vaultRoot().isBlank()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_PARSE_FAILED,
                    "vaultRoot is missing in knowledge index"
            );
        }
        Path vaultRoot = Path.of(snapshot.vaultRoot()).toAbsolutePath().normalize();
        Path resolvedPath = vaultRoot.resolve(node.filePath()).normalize();
        if (!resolvedPath.startsWith(vaultRoot)) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_FILE_OUTSIDE_VAULT,
                    "Resolved note path is outside vault root: " + resolvedPath
            );
        }
        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_FILE_NOT_FOUND,
                    "Resolved note file not found: " + resolvedPath
            );
        }
        try {
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            return new ResolvedNote(node, resolvedPath, content == null ? "" : content);
        } catch (IOException e) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_PARSE_FAILED,
                    "Failed to read note file: " + resolvedPath,
                    e
            );
        }
    }

    public record ResolvedNote(
            KnowledgeMapService.KnowledgeNode node,
            Path resolvedPath,
            String content
    ) {
    }
}
