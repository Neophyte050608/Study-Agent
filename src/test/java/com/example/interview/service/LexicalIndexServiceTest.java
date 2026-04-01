package com.example.interview.service;

import com.example.interview.config.RagRetrievalProperties;
import com.example.interview.entity.LexicalIndexDO;
import com.example.interview.mapper.LexicalIndexMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LexicalIndexServiceTest {

    @Mock
    private LexicalIndexMapper lexicalIndexMapper;

    private RetrievalTokenizerService retrievalTokenizerService;
    private RagRetrievalProperties ragRetrievalProperties;
    private LexicalIndexService lexicalIndexService;

    @BeforeEach
    void setUp() {
        retrievalTokenizerService = new RetrievalTokenizerService();
        ragRetrievalProperties = new RagRetrievalProperties();
        lexicalIndexService = new LexicalIndexService(
                lexicalIndexMapper,
                retrievalTokenizerService,
                ragRetrievalProperties
        );
    }

    @Test
    void shouldPreferFullTextWhenAutoModeEnabled() {
        ragRetrievalProperties.setLexicalSearchMode(RagRetrievalProperties.LexicalSearchMode.AUTO);

        LexicalIndexDO record = new LexicalIndexDO();
        record.setDocId("doc-1");
        record.setText("Redis 的核心优势是内存访问速度快");
        record.setFilePath("note/redis.md");
        record.setSourceType("obsidian");
        when(lexicalIndexMapper.searchByFullText(anyString(), anyInt())).thenReturn(List.of(record));

        List<Document> docs = lexicalIndexService.search("Redis为什么快", 3);

        assertEquals(1, docs.size());
        verify(lexicalIndexMapper).searchByFullText(anyString(), anyInt());
        verify(lexicalIndexMapper, never()).selectList(any());
    }

    @Test
    void shouldFallbackToLikeWhenFullTextFailsInAutoMode() {
        ragRetrievalProperties.setLexicalSearchMode(RagRetrievalProperties.LexicalSearchMode.AUTO);

        LexicalIndexDO record = new LexicalIndexDO();
        record.setDocId("doc-2");
        record.setText("事务传播包括 REQUIRED 和 REQUIRES_NEW");
        record.setFilePath("note/tx.md");
        record.setSourceType("obsidian");
        when(lexicalIndexMapper.searchByFullText(anyString(), anyInt())).thenThrow(new RuntimeException("no fulltext index"));
        when(lexicalIndexMapper.selectList(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LexicalIndexDO>>any()))
                .thenReturn(List.of(record));

        List<Document> docs = lexicalIndexService.search("事务传播行为", 3);

        assertEquals(1, docs.size());
        verify(lexicalIndexMapper).searchByFullText(anyString(), anyInt());
        verify(lexicalIndexMapper).selectList(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LexicalIndexDO>>any());
    }
}
