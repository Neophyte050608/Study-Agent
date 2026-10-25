package io.github.imzmq.interview.ingestion.application.contract;

import io.github.imzmq.interview.ingestion.application.contract.result.LoadedNote;

import java.util.List;

/** Scans local knowledge notes without exposing Spring resource types. */
public interface NoteLoadOperations {

    List<LoadedNote> loadNotes(String vaultPath);

    List<LoadedNote> loadNotes(String vaultPath, List<String> ignoredDirs);

    List<LoadedNote> loadNotes(String vaultPath, List<String> includeDirs, List<String> ignoredDirs);
}
