package io.github.imzmq.interview.service;

import io.github.imzmq.interview.knowledge.application.indexing.KnowledgeMapService;
import io.github.imzmq.interview.knowledge.application.localgraph.WikiLinkExpander;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikiLinkExpanderTest {

    @Test
    void shouldResolveBasicWikiLinksByTitleAndAlias() {
        WikiLinkExpander expander = new WikiLinkExpander();
        KnowledgeMapService.KnowledgeMapSnapshot snapshot = new KnowledgeMapService.KnowledgeMapSnapshot(
                Path.of("knowledge_map.json"),
                1,
                "build-1",
                "vault",
                List.of(
                        new KnowledgeMapService.KnowledgeNode("n1", "缓存击穿", List.of("hot key breakdown"), "summary", List.of(), "a.md", List.of(), List.of()),
                        new KnowledgeMapService.KnowledgeNode("n2", "布隆过滤器", List.of("BloomFilter"), "summary", List.of(), "b.md", List.of(), List.of())
                )
        );

        List<KnowledgeMapService.KnowledgeNode> expanded = expander.expandOneHop(
                "主笔记提到 [[缓存击穿]]，并且也提到 [[BloomFilter|布隆]]。",
                snapshot,
                5
        );

        assertEquals(2, expanded.size());
        assertEquals("n1", expanded.getFirst().id());
        assertEquals("n2", expanded.get(1).id());
    }

    @Test
    void shouldKeepHeadingAnchorWhenResolvingWikiLinks() {
        WikiLinkExpander expander = new WikiLinkExpander();
        KnowledgeMapService.KnowledgeMapSnapshot snapshot = new KnowledgeMapService.KnowledgeMapSnapshot(
                Path.of("knowledge_map.json"),
                1,
                "build-1",
                "vault",
                List.of(new KnowledgeMapService.KnowledgeNode("n1", "缓存击穿", List.of(), "summary", List.of(), "a.md", List.of(), List.of()))
        );

        List<WikiLinkExpander.ResolvedWikiLink> expanded = expander.expandResolvedOneHop(
                "主笔记提到 [[缓存击穿#解决方案]]。",
                snapshot,
                5
        );

        assertEquals(1, expanded.size());
        assertEquals("n1", expanded.getFirst().node().id());
        assertEquals("解决方案".toLowerCase(), expanded.getFirst().headingAnchor());
    }
}





