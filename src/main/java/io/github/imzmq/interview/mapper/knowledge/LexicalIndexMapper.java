package io.github.imzmq.interview.mapper.knowledge;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.entity.knowledge.LexicalIndexDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 词法索引 Mapper 接口。
 *
 * <p>除了 MyBatis-Plus 的基础 CRUD，本接口还补充了 FULLTEXT 查询能力，用于承接检索性能优化。</p>
 */
@Mapper
public interface LexicalIndexMapper extends BaseMapper<LexicalIndexDO> {

    /**
     * 使用 MySQL FULLTEXT 对正文进行召回。
     *
     * @param searchExpression FULLTEXT 检索表达式
     * @param limit 最大返回条数
     * @return 命中的词法索引记录
     */
    @Select("""
            <script>
            SELECT id, doc_id, text, file_path, knowledge_tags, source_type, parent_id, child_index, chunk_strategy, created_at
            FROM t_lexical_index
            WHERE MATCH(text) AGAINST(#{searchExpression} IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(text) AGAINST(#{searchExpression} IN NATURAL LANGUAGE MODE) DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<LexicalIndexDO> searchByFullText(
            @Param("searchExpression") String searchExpression,
            @Param("limit") int limit
    );
}


