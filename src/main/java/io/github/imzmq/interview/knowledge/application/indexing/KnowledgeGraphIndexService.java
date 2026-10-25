package io.github.imzmq.interview.knowledge.application.indexing;

import io.github.imzmq.interview.knowledge.application.contract.KnowledgeGraphWarmup;
import io.github.imzmq.interview.knowledge.application.contract.command.KnowledgeGraphIndexCommand;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConcept;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptRepository;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * Owns Neo4j concept graph writes and warmup queries inside the Knowledge module.
 */
@Service
public class KnowledgeGraphIndexService implements KnowledgeGraphIndexCommand, KnowledgeGraphWarmup {

    private final TechConceptRepository techConceptRepository;

    public KnowledgeGraphIndexService(TechConceptRepository techConceptRepository) {
        this.techConceptRepository = techConceptRepository;
    }

    @Override
    public void index(KnowledgeGraphSourceDocument document) {
        String filePath = document.filePath();
        if (filePath == null) {
            return;
        }

        String fileName = new File(filePath).getName();
        int dotIndex = fileName.lastIndexOf('.');
        String mainConceptName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        TechConcept mainConcept = techConceptRepository.findById(mainConceptName)
                .orElse(new TechConcept(mainConceptName));

        if (mainConcept.getDescription() == null) {
            String content = document.content();
            mainConcept.setDescription(content.length() > 200 ? content.substring(0, 200) + "..." : content);
        }

        String wikiLinks = document.wikiLinks();
        if (wikiLinks != null && !wikiLinks.isBlank()) {
            for (String link : wikiLinks.split(",")) {
                String relatedName = link.trim();
                if (!relatedName.isBlank() && !relatedName.equals(mainConceptName)) {
                    TechConcept relatedConcept = techConceptRepository.findById(relatedName)
                            .orElse(new TechConcept(relatedName));
                    mainConcept.addRelatedConcept(relatedConcept);
                    techConceptRepository.save(relatedConcept);
                }
            }
        }
        techConceptRepository.save(mainConcept);
    }

    @Override
    public void warmup() {
        techConceptRepository.findRelatedConceptSnippetsWithinTwoHops("warmup");
    }
}
