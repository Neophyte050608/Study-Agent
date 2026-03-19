package com.example.interview.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeLoader {

    public List<Document> loadResume(String resumePath) {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(
                new FileSystemResource(resumePath),
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0)
                        .withPageBottomMargin(0)
                        .build());

        return pdfReader.get();
    }
}
