package io.github.imzmq.interview.knowledge.application.indexing;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.imzmq.interview.media.application.contract.extension.ParentImageReferenceSupport.ParentImageReferences;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO;
import io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParentImageReferenceServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "parent-image-reference-test");
        TableInfoHelper.initTableInfo(assistant, RagParentDO.class);
    }

    @Test
    void findsStableParentImageReferenceViewsByFilePaths() {
        RagParentMapper mapper = mock(RagParentMapper.class);
        RagParentDO parent = parent(1L, "parent-1", "notes/cache.md", List.of("image-1", "image-2"));
        when(mapper.selectList(any())).thenReturn(List.of(parent));
        ParentImageReferenceService service = new ParentImageReferenceService(mapper);

        List<ParentImageReferences> results = service.findByFilePaths(
                List.of("notes/cache.md", "/vault/notes/cache.md"));

        assertThat(results).containsExactly(
                new ParentImageReferences("parent-1", "notes/cache.md", List.of("image-1", "image-2")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<RagParentDO>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("file_path IN");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains("notes/cache.md", "/vault/notes/cache.md");
    }

    @Test
    void replacesReferencesForExistingParentsAndClearsMissingEntries() {
        RagParentMapper mapper = mock(RagParentMapper.class);
        RagParentDO selected = parent(1L, "parent-1", "notes/cache.md", List.of("old-image"));
        RagParentDO selectedWithoutReplacement = parent(2L, "parent-2", "notes/db.md", List.of("old-image"));
        when(mapper.selectList(any())).thenReturn(List.of(selected, selectedWithoutReplacement));
        ParentImageReferenceService service = new ParentImageReferenceService(mapper);
        Map<String, List<String>> replacements = new LinkedHashMap<>();
        replacements.put("parent-1", List.of("image-2", "image-1"));
        replacements.put("parent-2", List.of());

        service.replaceByParentIds(replacements);

        assertThat(selected.getImageRefs()).containsExactly("image-2", "image-1");
        assertThat(selectedWithoutReplacement.getImageRefs()).isEmpty();
        verify(mapper).updateById(selected);
        verify(mapper).updateById(selectedWithoutReplacement);
    }

    @Test
    void skipsPersistenceForEmptyInputs() {
        RagParentMapper mapper = mock(RagParentMapper.class);
        ParentImageReferenceService service = new ParentImageReferenceService(mapper);

        assertThat(service.findByFilePaths(List.of())).isEmpty();
        service.replaceByParentIds(Map.of());

        verify(mapper, never()).selectList(any());
    }

    private static RagParentDO parent(Long id, String parentId, String filePath, List<String> imageRefs) {
        RagParentDO parent = new RagParentDO();
        parent.setId(id);
        parent.setParentId(parentId);
        parent.setFilePath(filePath);
        parent.setImageRefs(imageRefs);
        return parent;
    }
}
