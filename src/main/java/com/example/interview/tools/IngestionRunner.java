package com.example.interview.tools;

import com.example.interview.service.IngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class IngestionRunner implements CommandLineRunner {

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
                    System.out.println("Starting CLI Ingestion for: " + path);
                    ingestionService.sync(path);
                    System.out.println("CLI Ingestion Complete.");
                });
    }
}
