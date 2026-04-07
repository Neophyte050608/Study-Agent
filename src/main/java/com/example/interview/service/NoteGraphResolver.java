package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.rag.MarkdownSection;
import com.example.interview.rag.MarkdownSectionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最小笔记图解析器。
 *
 * <p>第一阶段做主笔记 + 一层 wiki links，并加入标题锚点、片段裁剪和总预算控制。</p>
 */
@Service
public class NoteGraphResolver {

    private final KnowledgeRetrievalProperties properties;
    private final LocalNoteResolverService localNoteResolverService;
    private final WikiLinkExpander wikiLinkExpander;

    public NoteGraphResolver(KnowledgeRetrievalProperties properties,
                             LocalNoteResolverService localNoteResolverService,
                             WikiLinkExpander wikiLinkExpander) {
        this.properties = properties;
        this.localNoteResolverService = localNoteResolverService;
        this.wikiLinkExpander = wikiLinkExpander;
    }

    public NoteGraphContext resolve(KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                    List<KnowledgeMapService.KnowledgeNode> primaryNodes,
                                    String question) {
        List<ResolvedNoteSlice> primaryNotes = new ArrayList<>();
        List<ResolvedNoteSlice> linkedNotes = new ArrayList<>();
        List<ResolvedNoteSlice> backlinkNotes = new ArrayList<>();
        List<ResolvedNoteSlice> tagNeighborNotes = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int remainingBudget = properties.getLocalContextBudgetChars();
        for (KnowledgeMapService.KnowledgeNode primary : primaryNodes) {
            LocalNoteResolverService.ResolvedNote resolvedPrimary = localNoteResolverService.resolve(snapshot, primary);
            String primaryExcerpt = buildExcerpt(resolvedPrimary.content(), null, question, Math.min(properties.getMaxNoteChars(), remainingBudget));
            if (!primaryExcerpt.isBlank()) {
                primaryNotes.add(new ResolvedNoteSlice(resolvedPrimary, primaryExcerpt, "primary"));
                remainingBudget -= primaryExcerpt.length();
            }
            seenIds.add(primary.id());
            if (remainingBudget <= 0) {
                break;
            }

            List<WikiLinkExpander.ResolvedWikiLink> linked = wikiLinkExpander.expandResolvedOneHop(
                    resolvedPrimary.content(),
                    snapshot,
                    properties.getMaxLinkedNotes()
            );
            for (WikiLinkExpander.ResolvedWikiLink linkedRef : linked) {
                KnowledgeMapService.KnowledgeNode linkedNode = linkedRef.node();
                if (seenIds.contains(linkedNode.id())) {
                    continue;
                }
                LocalNoteResolverService.ResolvedNote linkedNote = localNoteResolverService.resolve(snapshot, linkedNode);
                String linkedExcerpt = buildExcerpt(
                        linkedNote.content(),
                        linkedRef.headingAnchor(),
                        question,
                        Math.min(properties.getMaxNoteChars() / 2, remainingBudget)
                );
                if (!linkedExcerpt.isBlank()) {
                    linkedNotes.add(new ResolvedNoteSlice(linkedNote, linkedExcerpt, "linked"));
                    remainingBudget -= linkedExcerpt.length();
                }
                seenIds.add(linkedNode.id());
                if (linkedNotes.size() >= properties.getMaxLinkedNotes() || remainingBudget <= 0) {
                    break;
                }
            }
            if (linkedNotes.size() >= properties.getMaxLinkedNotes() || remainingBudget <= 0) {
                break;
            }
        }

        for (KnowledgeMapService.KnowledgeNode primary : primaryNodes) {
            if (remainingBudget <= 0) {
                break;
            }
            for (KnowledgeMapService.KnowledgeNode backlinkNode : resolveByNames(snapshot, primary.backlinks())) {
                if (seenIds.contains(backlinkNode.id())) {
                    continue;
                }
                LocalNoteResolverService.ResolvedNote resolved = localNoteResolverService.resolve(snapshot, backlinkNode);
                String excerpt = buildExcerpt(resolved.content(), null, question, Math.min(properties.getMaxNoteChars() / 2, remainingBudget));
                if (!excerpt.isBlank()) {
                    backlinkNotes.add(new ResolvedNoteSlice(resolved, excerpt, "backlink"));
                    remainingBudget -= excerpt.length();
                }
                seenIds.add(backlinkNode.id());
                if (backlinkNotes.size() >= properties.getMaxBacklinkNotes() || remainingBudget <= 0) {
                    break;
                }
            }
        }

        for (KnowledgeMapService.KnowledgeNode primary : primaryNodes) {
            if (remainingBudget <= 0) {
                break;
            }
            for (KnowledgeMapService.KnowledgeNode neighbor : resolveTagNeighbors(snapshot, primary, seenIds)) {
                LocalNoteResolverService.ResolvedNote resolved = localNoteResolverService.resolve(snapshot, neighbor);
                String excerpt = buildExcerpt(resolved.content(), null, question, Math.min(properties.getMaxNoteChars() / 2, remainingBudget));
                if (!excerpt.isBlank()) {
                    tagNeighborNotes.add(new ResolvedNoteSlice(resolved, excerpt, "tag_neighbor"));
                    remainingBudget -= excerpt.length();
                }
                seenIds.add(neighbor.id());
                if (tagNeighborNotes.size() >= properties.getMaxTagNeighborNotes() || remainingBudget <= 0) {
                    break;
                }
            }
        }

        return new NoteGraphContext(
                List.copyOf(primaryNotes),
                List.copyOf(linkedNotes),
                List.copyOf(backlinkNotes),
                List.copyOf(tagNeighborNotes)
        );
    }

    public record NoteGraphContext(
            List<ResolvedNoteSlice> primaryNotes,
            List<ResolvedNoteSlice> linkedNotes,
            List<ResolvedNoteSlice> backlinkNotes,
            List<ResolvedNoteSlice> tagNeighborNotes
    ) {
    }

    public record ResolvedNoteSlice(
            LocalNoteResolverService.ResolvedNote note,
            String excerpt,
            String role
    ) {
    }

    private String buildExcerpt(String content, String headingAnchor, String question, int budget) {
        if (content == null || content.isBlank() || budget <= 0) {
            return "";
        }
        List<MarkdownSection> sections = MarkdownSectionBuilder.buildSections(content, 4);
        if (sections.isEmpty()) {
            return truncate(clean(content), budget);
        }

        List<String> chunks = new ArrayList<>();
        if (headingAnchor != null && !headingAnchor.isBlank()) {
            for (MarkdownSection section : sections) {
                if (normalize(section.getHeading()).equals(normalize(headingAnchor))) {
                    chunks.add(renderSection(section));
                    break;
                }
            }
        }
        if (chunks.isEmpty()) {
            String normalizedQuestion = normalize(question);
            for (MarkdownSection section : sections) {
                if (matchesQuestion(section, normalizedQuestion)) {
                    chunks.add(renderSection(section));
                }
                if (chunks.size() >= 2) {
                    break;
                }
            }
        }
        if (chunks.isEmpty()) {
            for (MarkdownSection section : sections) {
                chunks.add(renderSection(section));
                if (chunks.size() >= 2) {
                    break;
                }
            }
        }
        String merged = String.join("\n\n", chunks);
        return truncate(clean(merged), budget);
    }

    private boolean matchesQuestion(MarkdownSection section, String normalizedQuestion) {
        if (normalizedQuestion.isBlank()) {
            return false;
        }
        String heading = normalize(section.getHeading());
        String path = normalize(section.getFormattedPath());
        if (!heading.isBlank() && normalizedQuestion.contains(heading)) {
            return true;
        }
        if (!path.isBlank() && normalizedQuestion.contains(path)) {
            return true;
        }
        for (String token : normalizedQuestion.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (heading.contains(token) || path.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String renderSection(MarkdownSection section) {
        String heading = section.getHeading() == null || section.getHeading().isBlank()
                ? ""
                : "## " + section.getFormattedPath() + "\n";
        return heading + section.getContent();
    }

    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String truncate(String text, int budget) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= budget) {
            return text;
        }
        return text.substring(0, budget);
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private List<KnowledgeMapService.KnowledgeNode> resolveByNames(KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                                                   List<String> names) {
        if (snapshot == null || snapshot.nodes() == null || names == null || names.isEmpty()) {
            return List.of();
        }
        Set<String> normalizedNames = new LinkedHashSet<>();
        for (String name : names) {
            String normalized = normalize(name);
            if (!normalized.isBlank()) {
                normalizedNames.add(normalized);
            }
        }
        if (normalizedNames.isEmpty()) {
            return List.of();
        }
        List<KnowledgeMapService.KnowledgeNode> results = new ArrayList<>();
        for (KnowledgeMapService.KnowledgeNode node : snapshot.nodes()) {
            if (normalizedNames.contains(normalize(node.title()))) {
                results.add(node);
                continue;
            }
            boolean aliasMatched = node.aliases().stream().map(this::normalize).anyMatch(normalizedNames::contains);
            if (aliasMatched) {
                results.add(node);
            }
        }
        return results;
    }

    private List<KnowledgeMapService.KnowledgeNode> resolveTagNeighbors(KnowledgeMapService.KnowledgeMapSnapshot snapshot,
                                                                        KnowledgeMapService.KnowledgeNode primary,
                                                                        Set<String> seenIds) {
        if (snapshot == null || snapshot.nodes() == null || primary == null || primary.tags() == null || primary.tags().isEmpty()) {
            return List.of();
        }
        Set<String> normalizedTags = primary.tags().stream().map(this::normalize).filter(item -> !item.isBlank()).collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (normalizedTags.isEmpty()) {
            return List.of();
        }
        List<KnowledgeMapService.KnowledgeNode> results = new ArrayList<>();
        for (KnowledgeMapService.KnowledgeNode node : snapshot.nodes()) {
            if (node.id().equals(primary.id()) || (seenIds != null && seenIds.contains(node.id()))) {
                continue;
            }
            long overlap = node.tags().stream().map(this::normalize).filter(normalizedTags::contains).count();
            if (overlap > 0) {
                results.add(node);
            }
            if (results.size() >= properties.getMaxTagNeighborNotes()) {
                break;
            }
        }
        return results;
    }
}
