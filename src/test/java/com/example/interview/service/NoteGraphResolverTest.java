package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteGraphResolverTest {

    @Test
    void shouldPreferAnchoredSectionAndRespectBudget() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setMaxLinkedNotes(1);
        properties.setLocalContextBudgetChars(300);
        properties.setMaxNoteChars(200);

        NoteGraphResolver resolver = new NoteGraphResolver(
                properties,
                new FakeLocalNoteResolverService(),
                new WikiLinkExpander()
        );

        KnowledgeMapService.KnowledgeNode primary = new KnowledgeMapService.KnowledgeNode(
                "n1", "缓存雪崩", List.of(), """
                # 缓存雪崩
                说明
                """, List.of("redis"), "primary.md", List.of("缓存击穿"), List.of("缓存问题总览"));
        KnowledgeMapService.KnowledgeNode linked = new KnowledgeMapService.KnowledgeNode(
                "n2", "缓存击穿", List.of(), """
                # 缓存击穿
                ## 定义
                定义段
                ## 解决方案
                这里是解决方案内容，应该优先被选中。
                ## 其他
                其他内容
                """, List.of("redis"), "linked.md", List.of(), List.of());
        KnowledgeMapService.KnowledgeNode backlink = new KnowledgeMapService.KnowledgeNode(
                "n3", "缓存问题总览", List.of(), """
                # 缓存问题总览
                这里回链到了缓存雪崩。
                """, List.of("redis"), "backlink.md", List.of(), List.of());
        KnowledgeMapService.KnowledgeNode neighbor = new KnowledgeMapService.KnowledgeNode(
                "n4", "缓存穿透", List.of(), """
                # 缓存穿透
                同标签邻居内容。
                """, List.of("redis"), "neighbor.md", List.of(), List.of());
        KnowledgeMapService.KnowledgeMapSnapshot snapshot = new KnowledgeMapService.KnowledgeMapSnapshot(
                Path.of("knowledge_map.json"),
                1,
                "build-1",
                "vault",
                List.of(primary, linked, backlink, neighbor)
        );

        NoteGraphResolver.NoteGraphContext context = resolver.resolve(snapshot, List.of(primary), "缓存雪崩如何处理");

        assertEquals(1, context.primaryNotes().size());
        assertEquals(1, context.linkedNotes().size());
        assertEquals(1, context.backlinkNotes().size());
        assertEquals(1, context.tagNeighborNotes().size());
        assertTrue(context.linkedNotes().getFirst().excerpt().contains("解决方案"));
        assertTrue(context.linkedNotes().getFirst().excerpt().length() <= 100);
    }

    private static final class FakeLocalNoteResolverService extends LocalNoteResolverService {

        @Override
        public ResolvedNote resolve(KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                    KnowledgeMapService.KnowledgeNode node) {
            String content = switch (node.id()) {
                case "n1" -> """
                        # 缓存雪崩
                        主笔记内容
                        [[缓存击穿#解决方案]]
                        """;
                case "n2" -> """
                        # 缓存击穿
                        ## 定义
                        定义段
                        ## 解决方案
                        这里是解决方案内容，应该优先被选中。
                        ## 其他
                        其他内容
                        """;
                case "n3" -> """
                        # 缓存问题总览
                        这里回链到了缓存雪崩。
                        """;
                case "n4" -> """
                        # 缓存穿透
                        同标签邻居内容。
                        """;
                default -> "";
            };
            return new ResolvedNote(node, Path.of(node.filePath()), content);
        }
    }
}
