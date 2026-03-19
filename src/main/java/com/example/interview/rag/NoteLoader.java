package com.example.interview.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class NoteLoader {

    private static final Logger logger = LoggerFactory.getLogger(NoteLoader.class);
    private static final List<String> DEFAULT_IGNORED_DIRS = List.of(".obsidian", ".trash", ".git", ".claude", "node_modules");

    public List<Resource> loadNotes(String vaultPath) {
        return loadNotes(vaultPath, DEFAULT_IGNORED_DIRS);
    }

    public List<Resource> loadNotes(String vaultPath, List<String> ignoredDirs) {
        logger.info("Scanning for markdown files in: {}", vaultPath);
        Path root = Paths.get(vaultPath);
        Set<String> ignored = new HashSet<>();
        ignored.addAll(DEFAULT_IGNORED_DIRS.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        if (ignoredDirs != null) {
            ignored.addAll(ignoredDirs.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet()));
        }
        try (Stream<Path> paths = Files.walk(Paths.get(vaultPath))) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(path, root, ignored))
                    .filter(path -> path.toString().endsWith(".md"))
                    .map(FileSystemResource::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error reading notes from path: {}", vaultPath, e);
            throw new RuntimeException("Failed to load notes", e);
        }
    }

    private boolean isIgnored(Path filePath, Path root, Set<String> ignored) {
        Path relative = root.relativize(filePath);
        for (Path part : relative) {
            String name = part.toString();
            if (ignored.contains(name.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
