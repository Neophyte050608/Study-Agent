package io.github.imzmq.interview.knowledge.application.localgraph;
import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;

import io.github.imzmq.interview.config.knowledge.KnowledgeRetrievalProperties;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.media.application.ImageService;
import io.github.imzmq.interview.knowledge.application.indexing.KnowledgeMapService;
import io.github.imzmq.interview.knowledge.domain.LocalGraphFailureReason;
import io.github.imzmq.interview.knowledge.domain.LocalGraphRetrievalException;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinition;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 本地知识图最小服务。
 *
 * <p>第一阶段读取主笔记正文，并支持最基础的一层 wiki links 展开。</p>
 */
@Service
public class LocalGraphKnowledgeService {

    private final KnowledgeRetrievalProperties properties;
    private final KnowledgeMapService knowledgeMapService;
    private final LocalCandidateRecallService localCandidateRecallService;
    private final OllamaRoutingService ollamaRoutingService;
    private final NoteGraphResolver noteGraphResolver;
    private final RAGObservabilityService ragObservabilityService;
    private final ImageService imageService;

    public LocalGraphKnowledgeService(KnowledgeRetrievalProperties properties,
                                      KnowledgeMapService knowledgeMapService,
                                      LocalCandidateRecallService localCandidateRecallService,
                                      OllamaRoutingService ollamaRoutingService,
                                      NoteGraphResolver noteGraphResolver,
                                      RAGObservabilityService ragObservabilityService,
                                      ImageService imageService) {
        this.properties = properties;
        this.knowledgeMapService = knowledgeMapService;
        this.localCandidateRecallService = localCandidateRecallService;
        this.ollamaRoutingService = ollamaRoutingService;
        this.noteGraphResolver = noteGraphResolver;
        this.ragObservabilityService = ragObservabilityService;
        this.imageService = imageService;
    }

    public KnowledgeContextPacket retrieve(String question, KnowledgeRetrievalMode requestedMode) {
        String traceId = RAGTraceContext.getTraceId();

        KnowledgeMapService.KnowledgeMapSnapshot snapshot = trace(TraceNodeDefinitions.LOCAL_INDEX_LOAD,
                question,
                () -> knowledgeMapService.loadValidatedIndex(),
                result -> "buildId=" + result.buildId() + ", nodes=" + result.nodeCount());

        List<KnowledgeMapService.KnowledgeNode> candidates = trace(TraceNodeDefinitions.LOCAL_CANDIDATE_RECALL,
                question,
                () -> localCandidateRecallService.recall(question, snapshot),
                result -> "candidates=" + result.size());

        List<String> matchedIds = trace(TraceNodeDefinitions.LOCAL_OLLAMA_ROUTE,
                question,
                () -> ollamaRoutingService.route(question, candidates),
                result -> "matches=" + result.size() + ", ids=" + String.join(",", result));

        List<KnowledgeMapService.KnowledgeNode> matchedNodes = candidates.stream()
                .filter(node -> matchedIds.contains(node.id()))
                .limit(properties.getMaxLocalMatches())
                .toList();
        if (matchedNodes.isEmpty()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.ROUTING_EMPTY,
                    "Local routing produced no matched nodes"
            );
        }

        NoteGraphResolver.NoteGraphContext graphContext = trace(TraceNodeDefinitions.LOCAL_NOTE_GRAPH,
                question,
                () -> noteGraphResolver.resolve(snapshot, matchedNodes, question),
                result -> "primary=" + result.primaryNotes().size()
                        + ", linked=" + result.linkedNotes().size()
                        + ", backlinks=" + result.backlinkNotes().size()
                        + ", tagNeighbors=" + result.tagNeighborNotes().size());

        String context = buildContext(graphContext);
        if (context.length() < properties.getMinLocalContextChars()) {
            throw new LocalGraphRetrievalException(
                LocalGraphFailureReason.CONTEXT_TOO_THIN,
                "Local note context is too thin"
            );
        }
        List<ImageService.ImageResult> retrievedImages = findAssociatedImages(question, graphContext, snapshot.vaultRoot());

        return new KnowledgeContextPacket(
                requestedMode,
                requestedMode,
                true,
                false,
                "",
                question,
                context,
                buildImageContext(retrievedImages),
                buildEvidence(graphContext),
                retrievedImages,
                false,
                countRetrievedNotes(graphContext),
                summarizeResolvedNotes(graphContext)
        );
    }

    private <T> T trace(TraceNodeDefinition definition,
                        String inputSummary,
                        java.util.concurrent.Callable<T> action,
                        java.util.function.Function<T, String> outputBuilder) {
        String traceId = RAGTraceContext.getTraceId();
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(
                traceId,
                nodeId,
                RAGTraceContext.getCurrentNodeId(),
                definition.nodeType(),
                definition.nodeName()
        );
        try {
            T result = action.call();
            ragObservabilityService.endNode(
                    traceId,
                    nodeId,
                    inputSummary,
                    outputBuilder == null ? "" : outputBuilder.apply(result),
                    null
            );
            return result;
        } catch (LocalGraphRetrievalException e) {
            ragObservabilityService.endNode(
                    traceId,
                    nodeId,
                    inputSummary,
                    null,
                    e.getFailureReason().name()
            );
            throw e;
        } catch (Exception e) {
            ragObservabilityService.endNode(
                    traceId,
                    nodeId,
                    inputSummary,
                    null,
                    e.getMessage()
            );
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.NOTE_PARSE_FAILED,
                    "Local graph tracing step failed: " + definition.nodeName(),
                    e
            );
        }
    }

    private String buildContext(NoteGraphResolver.NoteGraphContext graphContext) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.primaryNotes()) {
            builder.append("[").append(index++).append("] 主笔记: ")
                    .append(slice.note().node().title())
                    .append("\n")
                    .append(slice.excerpt())
                    .append("\n\n");
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.linkedNotes()) {
            builder.append("[").append(index++).append("] 关联笔记: ")
                    .append(slice.note().node().title())
                    .append("\n")
                    .append(slice.excerpt())
                    .append("\n\n");
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.backlinkNotes()) {
            builder.append("[").append(index++).append("] 反链笔记: ")
                    .append(slice.note().node().title())
                    .append("\n")
                    .append(slice.excerpt())
                    .append("\n\n");
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.tagNeighborNotes()) {
            builder.append("[").append(index++).append("] 同标签邻居: ")
                    .append(slice.note().node().title())
                    .append("\n")
                    .append(slice.excerpt())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String buildEvidence(NoteGraphResolver.NoteGraphContext graphContext) {
        Set<String> lines = new LinkedHashSet<>();
        int index = 1;
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.primaryNotes()) {
            lines.add(index++ + ". [local_graph:" + slice.note().node().filePath() + "] " + slice.note().node().title());
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.linkedNotes()) {
            lines.add(index++ + ". [local_graph_linked:" + slice.note().node().filePath() + "] " + slice.note().node().title());
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.backlinkNotes()) {
            lines.add(index++ + ". [local_graph_backlink:" + slice.note().node().filePath() + "] " + slice.note().node().title());
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : graphContext.tagNeighborNotes()) {
            lines.add(index++ + ". [local_graph_tag_neighbor:" + slice.note().node().filePath() + "] " + slice.note().node().title());
        }
        return lines.stream().collect(Collectors.joining("\n"));
    }

    private int countRetrievedNotes(NoteGraphResolver.NoteGraphContext graphContext) {
        if (graphContext == null) {
            return 0;
        }
        return graphContext.primaryNotes().size()
                + graphContext.linkedNotes().size()
                + graphContext.backlinkNotes().size()
                + graphContext.tagNeighborNotes().size();
    }

    private List<String> summarizeResolvedNotes(NoteGraphResolver.NoteGraphContext graphContext) {
        if (graphContext == null) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        appendResolvedNotes(refs, graphContext.primaryNotes(), "primary");
        appendResolvedNotes(refs, graphContext.linkedNotes(), "linked");
        appendResolvedNotes(refs, graphContext.backlinkNotes(), "backlink");
        appendResolvedNotes(refs, graphContext.tagNeighborNotes(), "tag");
        return refs.stream().limit(5).toList();
    }

    private void appendResolvedNotes(Set<String> refs,
                                     List<NoteGraphResolver.ResolvedNoteSlice> slices,
                                     String category) {
        if (slices == null || slices.isEmpty()) {
            return;
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : slices) {
            if (slice == null || slice.note() == null || slice.note().node() == null) {
                continue;
            }
            String title = slice.note().node().title();
            String path = slice.note().node().filePath();
            String excerpt = slice.excerpt() == null ? "" : slice.excerpt().replaceAll("\\s+", " ").trim();
            if (excerpt.length() > 80) {
                excerpt = excerpt.substring(0, 80) + "...";
            }
            String ref = "[" + category + "] " + title + (path == null || path.isBlank() ? "" : " | " + path);
            if (!excerpt.isBlank()) {
                ref = ref + " | " + excerpt;
            }
            refs.add(ref);
        }
    }

    private List<ImageService.ImageResult> findAssociatedImages(String question,
                                                                NoteGraphResolver.NoteGraphContext graphContext,
                                                                String vaultRoot) {
        if (imageService == null || graphContext == null) {
            return List.of();
        }
        LinkedHashMap<String, Double> notePathWeights = new LinkedHashMap<>();
        appendNotePaths(notePathWeights, graphContext.primaryNotes(), 1.0d);
        appendNotePaths(notePathWeights, graphContext.linkedNotes(), 0.82d);
        appendNotePaths(notePathWeights, graphContext.backlinkNotes(), 0.68d);
        appendNotePaths(notePathWeights, graphContext.tagNeighborNotes(), 0.55d);
        return imageService.findImagesForNotePaths(notePathWeights, question, vaultRoot);
    }

    private void appendNotePaths(java.util.Map<String, Double> notePaths,
                                 List<NoteGraphResolver.ResolvedNoteSlice> slices,
                                 double weight) {
        if (slices == null || slices.isEmpty()) {
            return;
        }
        for (NoteGraphResolver.ResolvedNoteSlice slice : slices) {
            if (slice == null || slice.note() == null || slice.note().node() == null) {
                continue;
            }
            String filePath = slice.note().node().filePath();
            if (filePath != null && !filePath.isBlank()) {
                notePaths.merge(filePath, weight, Math::max);
            }
        }
    }

    private String buildImageContext(List<ImageService.ImageResult> retrievedImages) {
        if (retrievedImages == null || retrievedImages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (ImageService.ImageResult image : retrievedImages) {
            builder.append("[图").append(index++).append("] ")
                    .append(image.summaryText() == null ? image.imageName() : image.summaryText())
                    .append(" - 来源: ").append(image.imageName())
                    .append("\n");
        }
        return builder.toString().trim();
    }
}














