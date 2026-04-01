package com.example.interview.tools;

import com.example.interview.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 知识库同步启动器 (CLI Runner)。
 * 
 * 职责：
 * 1. 命令行集成：支持通过启动参数 --ingest=/path/to/notes 触发同步任务。
 * 2. 离线入库：方便在不启动 Web UI 的情况下，通过命令行执行大规模知识同步。
 */
@Component
public class IngestionRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IngestionRunner.class);

    private final IngestionService ingestionService;

    public IngestionRunner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) throws Exception {
        // Usage: --ingest=/path/to/notes
        Arrays.stream(args)
                .filter(arg -> arg.startsWith("--ingest="))
                .findFirst()
                .ifPresent(arg -> {
                    String path = arg.split("=")[1];
                    logger.info("Starting CLI Ingestion for: {}", path);
                    ingestionService.sync(path);
                    logger.info("CLI Ingestion Complete.");
                });
    }
}
