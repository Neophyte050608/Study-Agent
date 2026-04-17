package io.github.imzmq.interview.rag.core;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 简历加载器。
 * 负责从本地 PDF 文件中读取并解析简历内容。
 */
@Component
public class ResumeLoader {

    /**
     * 加载 PDF 简历并转换为 Document 列表。
     * 
     * @param resumePath 简历文件在本地的绝对路径
     * @return 解析后的文档内容
     */
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

