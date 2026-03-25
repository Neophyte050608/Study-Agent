package com.example.interview.rag;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分器。
 * 针对超长文本，按自然段落、句子等边界逐步尝试拆分，以保留局部语义。
 */
public class RecursiveChunker {

    // 默认分隔符优先级：双换行(段落) -> 单换行(行) -> 常见句号(句子)
    private static final String[] SEPARATORS = {"\n\n", "\n", "。", ".", "！", "!", "？", "?"};

    private final int targetSize;
    private final int maxSize;
    private final int overlap;
    private final boolean fallbackTokenSplitEnabled;

    /** 兜底的 token 切分器 */
    private final TokenTextSplitter tokenTextSplitter;

    public RecursiveChunker(int targetSize, int maxSize, int overlap, boolean fallbackTokenSplitEnabled) {
        this.targetSize = targetSize;
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.fallbackTokenSplitEnabled = fallbackTokenSplitEnabled;
        // fallback 切分器直接用 targetSize 作为切分标准
        this.tokenTextSplitter = new TokenTextSplitter(targetSize, overlap, 5, 10000, true);
    }

    /**
     * 对文本进行递归切分。
     */
    public List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        // 近似估算 Token 长度（粗略按 1.5 字符/Token，中文字符通常更长）
        if (estimateTokens(text) <= targetSize) {
            result.add(text);
            return result;
        }

        doSplit(text, 0, result);
        return result;
    }

    private void doSplit(String text, int separatorIndex, List<String> result) {
        if (text == null || text.isBlank()) {
            return;
        }

        // 如果当前文本长度已满足要求，直接加入
        if (estimateTokens(text) <= targetSize || separatorIndex >= SEPARATORS.length) {
            // 如果所有的自然边界都试过了还是超长
            if (estimateTokens(text) > maxSize && fallbackTokenSplitEnabled) {
                fallbackSplit(text, result);
            } else {
                result.add(text.trim());
            }
            return;
        }

        String separator = SEPARATORS[separatorIndex];
        // 特殊处理正则元字符
        String regex = separator.equals(".") || separator.equals("?") ? "\\" + separator : separator;
        
        String[] parts = text.split(regex);
        if (parts.length <= 1) {
            // 当前分隔符未生效，尝试下一个
            doSplit(text, separatorIndex + 1, result);
            return;
        }

        StringBuilder currentChunk = new StringBuilder();
        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            // 拼回分隔符（除了最后一个片段）
            String partWithSep = i < parts.length - 1 ? part + separator : part;

            if (estimateTokens(currentChunk.toString() + partWithSep) > targetSize && currentChunk.length() > 0) {
                // 当前块已满，先存入
                chunks.add(currentChunk.toString().trim());
                
                // 处理 overlap：从上一个 chunk 尾部截取部分内容作为新 chunk 的开头
                String previousText = currentChunk.toString();
                currentChunk = new StringBuilder();
                
                // 粗略按比例计算 overlap 字符数
                int overlapChars = (int) (overlap * 1.5);
                if (previousText.length() > overlapChars) {
                    currentChunk.append(previousText.substring(previousText.length() - overlapChars));
                } else {
                    currentChunk.append(previousText);
                }
            }
            currentChunk.append(partWithSep);
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        // 对拆分出来的 chunk 再次检查
        for (String chunk : chunks) {
            if (estimateTokens(chunk) > maxSize) {
                // 如果拆出来的部分依然超大，用下一级分隔符继续拆
                doSplit(chunk, separatorIndex + 1, result);
            } else {
                result.add(chunk);
            }
        }
    }

    private void fallbackSplit(String text, List<String> result) {
        // 使用 Spring AI 的 TokenTextSplitter 进行物理截断
        List<String> tokens = tokenTextSplitter.apply(List.of(new org.springframework.ai.document.Document(text)))
                .stream().map(org.springframework.ai.document.Document::getText).toList();
        result.addAll(tokens);
    }

    /**
     * 粗略估算字符串的 Token 数量。
     * 中文环境下，近似认为 1 个中文字符约 1.5-2 tokens，英文单词 1 token。
     * 统一简单按照 1.5 字符 = 1 token 估算，偏向保守。
     */
    private int estimateTokens(String text) {
        if (text == null) return 0;
        return (int) (text.length() / 1.5);
    }
}
