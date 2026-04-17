package io.github.imzmq.interview.rag.core;

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

/**
 * 笔记加载器。
 * 负责从本地文件系统中扫描并加载 Markdown 笔记文件。
 */
@Component
public class NoteLoader {

    private static final Logger logger = LoggerFactory.getLogger(NoteLoader.class);
    /** 默认忽略的目录，避免加载无关的元数据或临时文件 */
    private static final List<String> DEFAULT_IGNORED_DIRS = List.of(".obsidian", ".trash", ".git", ".claude", "node_modules");

    /**
     * 使用默认忽略配置加载笔记。
     * 
     * @param vaultPath 笔记库根路径
     * @return 资源列表
     */
    public List<Resource> loadNotes(String vaultPath) {
        return loadNotes(vaultPath, DEFAULT_IGNORED_DIRS);
    }

    /**
     * 指定忽略目录加载笔记。
     * 
     * @param vaultPath 笔记库根路径
     * @param ignoredDirs 额外的忽略目录列表
     * @return 资源列表
     */
    public List<Resource> loadNotes(String vaultPath, List<String> ignoredDirs) {
        return loadNotes(vaultPath, null, ignoredDirs);
    }

    /**
     * 指定包含目录与忽略目录加载笔记。
     *
     * @param vaultPath 笔记库根路径
     * @param includeDirs 允许纳入的相对目录；为空时表示扫描整个 vault
     * @param ignoredDirs 额外忽略目录
     * @return 资源列表
     */
    public List<Resource> loadNotes(String vaultPath, List<String> includeDirs, List<String> ignoredDirs) {
        logger.info("Scanning for markdown files in: {}", vaultPath);
        Path root = Paths.get(vaultPath);
        Set<String> ignored = new HashSet<>();
        ignored.addAll(DEFAULT_IGNORED_DIRS.stream().map(String::toLowerCase).collect(Collectors.toSet()));
        Set<String> includes = new HashSet<>();
        if (includeDirs != null) {
            includes.addAll(includeDirs.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .map(item -> item.replace("\\", "/").toLowerCase())
                    .collect(Collectors.toSet()));
        }
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
                    .filter(path -> isIncluded(path, root, includes))
                    .filter(path -> !isIgnored(path, root, ignored))
                    .filter(path -> path.toString().endsWith(".md"))
                    .map(FileSystemResource::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error reading notes from path: {}", vaultPath, e);
            throw new RuntimeException("Failed to load notes", e);
        }
    }

    /**
     * 检查路径是否在忽略名单中。
     */
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

    /**
     * 检查路径是否命中允许纳入的目录。
     */
    private boolean isIncluded(Path filePath, Path root, Set<String> includes) {
        if (includes == null || includes.isEmpty()) {
            return true;
        }
        String relative = root.relativize(filePath).toString().replace("\\", "/").toLowerCase();
        for (String include : includes) {
            if (relative.equals(include) || relative.startsWith(include + "/")) {
                return true;
            }
        }
        return false;
    }
}

