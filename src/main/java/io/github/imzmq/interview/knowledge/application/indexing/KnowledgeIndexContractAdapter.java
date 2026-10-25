package io.github.imzmq.interview.knowledge.application.indexing;

import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeDocumentSplitOperations;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeIndexDocument;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeLexicalIndexOperations;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeParentChildIndexOperations;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/** Adapts Knowledge's internal Spring AI indexing services to its public framework-neutral contracts. */
@Service
public class KnowledgeIndexContractAdapter implements
        KnowledgeDocumentSplitOperations,
        KnowledgeLexicalIndexOperations,
        KnowledgeParentChildIndexOperations {

    private final DocumentSplitter documentSplitter;
    private final LexicalIndexService lexicalIndexService;
    private final ParentChildIndexService parentChildIndexService;

    public KnowledgeIndexContractAdapter(
            DocumentSplitter documentSplitter,
            LexicalIndexService lexicalIndexService,
            ParentChildIndexService parentChildIndexService
    ) {
        this.documentSplitter = documentSplitter;
        this.lexicalIndexService = lexicalIndexService;
        this.parentChildIndexService = parentChildIndexService;
    }

    @Override
    public List<KnowledgeIndexDocument> split(List<KnowledgeIndexDocument> documents) {
        return documentSplitter.split(toDocuments(documents)).stream()
                .map(KnowledgeIndexContractAdapter::toContractDocument)
                .toList();
    }

    @Override
    public void upsert(List<KnowledgeIndexDocument> documents) {
        lexicalIndexService.upsertDocuments(toDocuments(documents));
    }

    @Override
    public void removeByIds(List<String> documentIds) {
        lexicalIndexService.removeByIds(documentIds);
    }

    @Override
    public void rebuild(String filePath, List<KnowledgeIndexDocument> chunks) {
        parentChildIndexService.rebuildByChunks(filePath, toDocuments(chunks));
    }

    @Override
    public void deleteByFilePath(String filePath) {
        parentChildIndexService.deleteByFilePath(filePath);
    }

    @Override
    public long countParents() {
        return parentChildIndexService.countParents();
    }

    @Override
    public long countChildren() {
        return parentChildIndexService.countChildren();
    }

    private static List<Document> toDocuments(List<KnowledgeIndexDocument> documents) {
        if (documents == null) {
            return List.of();
        }
        return documents.stream().map(KnowledgeIndexContractAdapter::toDocument).toList();
    }

    private static Document toDocument(KnowledgeIndexDocument document) {
        return new Document(document.id(), document.text(), document.metadata());
    }

    private static KnowledgeIndexDocument toContractDocument(Document document) {
        return new KnowledgeIndexDocument(document.getId(), document.getText(), document.getMetadata());
    }
}
