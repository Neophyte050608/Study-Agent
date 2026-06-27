package io.github.imzmq.interview.knowledge.application.graph;

import io.github.imzmq.interview.knowledge.internal.graph.domain.TechConceptRepository;
import org.springframework.stereotype.Service;

/**
 * Application facade for warming up graph-backed knowledge retrieval.
 */
@Service
public class GraphKnowledgeWarmupService {

    private final TechConceptRepository techConceptRepository;

    public GraphKnowledgeWarmupService(TechConceptRepository techConceptRepository) {
        this.techConceptRepository = techConceptRepository;
    }

    public void warmup() {
        techConceptRepository.findRelatedConceptSnippetsWithinTwoHops("warmup");
    }
}
