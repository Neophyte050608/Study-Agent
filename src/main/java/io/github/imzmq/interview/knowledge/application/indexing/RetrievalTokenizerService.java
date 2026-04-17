package io.github.imzmq.interview.knowledge.application.indexing;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 检索分词统一服务。
 *
 * <p>职责说明：</p>
 * <p>1. 统一收口 RAG 重排、意图定向检索、评测等链路的分词口径，避免同一查询在不同阶段被切成不同 token。</p>
 * <p>2. 以 Jieba SEARCH 模式作为主分词策略，兼顾中文短语召回与英文/数字术语的可读性。</p>
 * <p>3. 当文本不适合直接走中文分词时，提供一个轻量的正则兜底，避免返回空 token 列表。</p>
 */
@Service
public class RetrievalTokenizerService {

    /**
     * 仅用于过滤纯标点或纯空白 token，避免无意义符号进入 TF-IDF 与 overlap 计算。
     */
    private static final Pattern PUNCT_OR_SPACE_PATTERN = Pattern.compile("^[\\p{Punct}\\s]+$");

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 对外暴露统一分词入口。
     *
     * @param text 原始检索文本
     * @return 归一化后的 token 列表；若文本为空则返回空列表
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> jiebaTokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH).stream()
                .map(token -> token.word)
                .map(word -> word == null ? "" : word.toLowerCase(Locale.ROOT).trim())
                .filter(this::isMeaningfulToken)
                .collect(Collectors.toList());
        if (!jiebaTokens.isEmpty()) {
            return jiebaTokens;
        }

        // 兜底逻辑仅在 Jieba 未产出有效 token 时启用，避免英文术语或特殊格式文本被整体丢弃。
        return List.of(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}\\s_#-]", " ")
                        .replaceAll("\\s+", " ")
                        .trim()
                        .split(" "))
                .stream()
                .map(String::trim)
                .filter(this::isMeaningfulToken)
                .collect(Collectors.toList());
    }

    /**
     * 统一定义“可参与检索评分的 token”。
     *
     * @param token 候选 token
     * @return true 表示该 token 可参与召回与重排计算
     */
    private boolean isMeaningfulToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (PUNCT_OR_SPACE_PATTERN.matcher(token).matches()) {
            return false;
        }
        return token.length() >= 2;
    }
}




