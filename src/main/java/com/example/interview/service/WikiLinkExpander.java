package com.example.interview.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 最小 wiki link 解析器。
 *
 * <p>第一阶段支持 [[note]]、[[note|alias]] 和 [[note#heading]]，忽略块引用。</p>
 */
@Service
public class WikiLinkExpander {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|#]+)(?:#([^\\]|]+))?(?:\\|.*?)?\\]\\]");

    public List<KnowledgeMapService.KnowledgeNode> expandOneHop(String content,
                                                                KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                                                int limit) {
        return extractLinks(content).stream()
                .map(link -> resolveLink(link, snapshot))
                .filter(item -> item != null)
                .limit(limit)
                .map(ResolvedWikiLink::node)
                .toList();
    }

    public List<ResolvedWikiLink> expandResolvedOneHop(String content,
                                                       KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                                       int limit) {
        if (content == null || content.isBlank() || snapshot == null || snapshot.nodes() == null || snapshot.nodes().isEmpty() || limit <= 0) {
            return List.of();
        }
        return extractLinks(content).stream()
                .map(link -> resolveLink(link, snapshot))
                .filter(item -> item != null)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<WikiLinkRef> extractLinks(String content) {
        Set<WikiLinkRef> linkRefs = new LinkedHashSet<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String linkName = normalize(matcher.group(1));
            String heading = normalize(matcher.group(2));
            if (!linkName.isBlank()) {
                linkRefs.add(new WikiLinkRef(linkName, heading));
            }
        }
        return List.copyOf(linkRefs);
    }

    private ResolvedWikiLink resolveLink(WikiLinkRef linkRef, KnowledgeMapService.KnowledgeMapSnapshot snapshot) {
        if (linkRef == null || snapshot == null || snapshot.nodes() == null) {
            return null;
        }
        for (KnowledgeMapService.KnowledgeNode node : snapshot.nodes()) {
            if (matches(linkRef.targetName(), node)) {
                return new ResolvedWikiLink(node, linkRef.headingAnchor());
            }
        }
        return null;
    }

    private boolean matches(String linkName, KnowledgeMapService.KnowledgeNode node) {
        String normalizedTitle = normalize(node.title());
        if (linkName.equals(normalizedTitle)) {
            return true;
        }
        for (String alias : node.aliases()) {
            if (linkName.equals(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    public record WikiLinkRef(String targetName, String headingAnchor) {
    }

    public record ResolvedWikiLink(KnowledgeMapService.KnowledgeNode node, String headingAnchor) {
    }
}
