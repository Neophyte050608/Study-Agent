package io.github.imzmq.interview.ingestion.application;

import io.github.imzmq.interview.ingestion.application.contract.IngestionConfigOperations;
import io.github.imzmq.interview.ingestion.application.contract.IngestionOperations;
import io.github.imzmq.interview.ingestion.application.contract.IngestionTaskOperations;
import io.github.imzmq.interview.ingestion.application.contract.NoteLoadOperations;
import io.github.imzmq.interview.ingestion.application.contract.result.LoadedNote;
import io.github.imzmq.interview.knowledge.application.contract.extension.KnowledgeDocumentIngestionSupport;
import io.github.imzmq.interview.knowledge.application.contract.extension.LocalKnowledgeSourceSupport;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeIngestionSupportAdapter
        implements KnowledgeDocumentIngestionSupport, LocalKnowledgeSourceSupport {

    private final IngestionOperations ingestionOperations;
    private final IngestionTaskOperations ingestionTaskOperations;
    private final NoteLoadOperations noteLoadOperations;
    private final IngestionConfigOperations ingestionConfigOperations;

    public KnowledgeIngestionSupportAdapter(
            IngestionOperations ingestionOperations,
            IngestionTaskOperations ingestionTaskOperations,
            NoteLoadOperations noteLoadOperations,
            IngestionConfigOperations ingestionConfigOperations
    ) {
        this.ingestionOperations = ingestionOperations;
        this.ingestionTaskOperations = ingestionTaskOperations;
        this.noteLoadOperations = noteLoadOperations;
        this.ingestionConfigOperations = ingestionConfigOperations;
    }

    @Override
    public boolean hasRunningTasks() {
        return ingestionTaskOperations.hasRunningTasks();
    }

    @Override
    public boolean deleteByFilePath(String filePath) {
        return ingestionOperations.deleteByFilePath(filePath);
    }

    @Override
    public boolean rechunkByFilePath(String filePath) {
        return ingestionOperations.rechunkByFilePath(filePath);
    }

    @Override
    public List<Path> loadNotePaths(String vaultPath, List<String> includeDirs, List<String> ignoredDirs) {
        return noteLoadOperations.loadNotes(vaultPath, includeDirs, ignoredDirs)
                .stream()
                .map(LoadedNote::path)
                .toList();
    }

    @Override
    public Map<String, String> getConfig() {
        return ingestionConfigOperations.getConfig();
    }

    @Override
    public String getLocalKnowledgeIndexPath() {
        return ingestionConfigOperations.getLocalKnowledgeIndexPath();
    }

    @Override
    public void saveLocalKnowledgeIndexPath(String indexFilePath) {
        ingestionConfigOperations.saveLocalKnowledgeIndexPath(indexFilePath);
    }
}
