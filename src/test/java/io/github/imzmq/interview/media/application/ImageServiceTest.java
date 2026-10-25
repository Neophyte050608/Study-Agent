package io.github.imzmq.interview.media.application;

import io.github.imzmq.interview.media.application.contract.result.ImageResult;
import io.github.imzmq.interview.media.application.contract.MediaDocument;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport.MediaSourcePaths;
import io.github.imzmq.interview.media.application.contract.extension.ParentImageReferenceSupport;
import io.github.imzmq.interview.media.application.contract.extension.ParentImageReferenceSupport.ParentImageReferences;
import io.github.imzmq.interview.media.infrastructure.persistence.ImageMetadataDO;
import io.github.imzmq.interview.media.infrastructure.persistence.ImageMetadataMapper;
import io.github.imzmq.interview.media.infrastructure.persistence.TextImageRelationDO;
import io.github.imzmq.interview.media.infrastructure.persistence.TextImageRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ImageServiceTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "image-service-test");
        TableInfoHelper.initTableInfo(assistant, ImageMetadataDO.class);
        TableInfoHelper.initTableInfo(assistant, TextImageRelationDO.class);
    }

    @Test
    void findsAssociatedImagesFromParentReferencesWithStableScoringDeduplicationAndLimit() {
        TestFixture fixture = new TestFixture(2);
        ParentImageReferences parent = new ParentImageReferences(
                "parent-1", "notes/cache.md", List.of("image-2", "image-1", "image-1", "image-3"));
        when(fixture.parentImageReferenceSupport.findByFilePaths(any())).thenReturn(List.of(parent));
        when(fixture.imageMetadataMapper.selectList(any())).thenReturn(List.of(
                image("image-1", "one.png", "one summary"),
                image("image-2", "two.png", "two summary"),
                image("image-3", "three.png", "three summary")
        ));
        when(fixture.imageIndexService.search("cache", 6, true)).thenReturn(List.of(
                new ImageIndexService.ImageHit("image-1", "one.png", 1.0d, "semantic")
        ));

        List<ImageResult> results = fixture.service.findImagesForNotePaths(
                Map.of("notes\\cache.md", 1.0d), "cache", null);

        assertThat(results).containsExactly(
                new ImageResult(
                        "image-1", "one.png", "/api/images/image-1/file",
                        "/api/images/image-1/thumbnail?maxWidth=400", "one summary",
                        null, 1.0d, "local_graph_semantic"),
                new ImageResult(
                        "image-2", "two.png", "/api/images/image-2/file",
                        "/api/images/image-2/thumbnail?maxWidth=400", "two summary",
                        null, 0.65d, "local_graph_associated")
        );
    }

    @Test
    void returnsNoAssociatedImagesWhenNoParentsMatch() {
        TestFixture fixture = new TestFixture(3);
        when(fixture.parentImageReferenceSupport.findByFilePaths(any())).thenReturn(List.of());

        assertThat(fixture.service.findImagesForNotePaths(
                Map.of("notes/cache.md", 1.0d), "cache", null)).isEmpty();
    }

    @Test
    void replacesParentImageReferencesAfterIndexingDocumentImages() {
        TestFixture fixture = new TestFixture(3);
        ImageReferenceExtractor.ImageReference reference = new ImageReferenceExtractor.ImageReference(
                "diagram.png", "diagram.png", "![[diagram.png]]", 8,
                Path.of("diagram.png"), "Cache", "cache diagram", "diagram summary");
        when(fixture.imageReferenceExtractor.extract(any(), any(), any()))
                .thenReturn(List.of(reference));
        when(fixture.imageIngestionPipeline.processImage(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        image("image-1", "diagram.png", "diagram summary")));

        Document chunk = new Document("content ![[diagram.png]]");
        chunk.getMetadata().put("child_id", "child-1");
        chunk.getMetadata().put("parent_id", "parent-1");
        chunk.getMetadata().put("section_path", "Cache");

        fixture.service.indexImagesForDocument(
                "notes/cache.md",
                "# Cache\n![[diagram.png]]",
                List.of(new MediaDocument(chunk.getId(), chunk.getText(), chunk.getMetadata())));

        ArgumentCaptor<TextImageRelationDO> relationCaptor = ArgumentCaptor.forClass(TextImageRelationDO.class);
        verify(fixture.textImageRelationMapper).insert(relationCaptor.capture());
        assertThat(relationCaptor.getValue().getParentDocId()).isEqualTo("parent-1");
        assertThat(relationCaptor.getValue().getImageId()).isEqualTo("image-1");
        assertThat(relationCaptor.getValue().getRefSyntax()).contains("##score=").contains("##reason=exact_ref");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> replacementsCaptor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(fixture.parentImageReferenceSupport).replaceByParentIds(replacementsCaptor.capture());
        assertThat(replacementsCaptor.getValue()).containsEntry("parent-1", List.of("image-1"));
    }

    @Test
    void usesMediaOwnedSourcePathContractWhenResolvingConfiguredImageBasePath() {
        TestFixture fixture = new TestFixture(3);
        when(fixture.mediaSourcePathSupport.currentSourcePaths())
                .thenReturn(new MediaSourcePaths("/vault", "/images"));
        when(fixture.imageReferenceExtractor.extract(any(), any(), any())).thenReturn(List.of());

        fixture.service.enrichMarkdownWithImageSummaries("![diagram](diagram.png)", "/vault/note.md");

        verify(fixture.imageReferenceExtractor).extract(
                "![diagram](diagram.png)", "/vault/note.md", "/images");
        verify(fixture.mediaSourcePathSupport).currentSourcePaths();
        verifyNoMoreInteractions(fixture.mediaSourcePathSupport);
    }

    private static ImageMetadataDO image(String imageId, String imageName, String summary) {
        ImageMetadataDO image = new ImageMetadataDO();
        image.setImageId(imageId);
        image.setImageName(imageName);
        image.setSummaryText(summary);
        return image;
    }

    private static final class TestFixture {
        private final ImageReferenceExtractor imageReferenceExtractor = mock(ImageReferenceExtractor.class);
        private final ImageIngestionPipeline imageIngestionPipeline = mock(ImageIngestionPipeline.class);
        private final ImageIndexService imageIndexService = mock(ImageIndexService.class);
        private final ImageMetadataMapper imageMetadataMapper = mock(ImageMetadataMapper.class);
        private final TextImageRelationMapper textImageRelationMapper = mock(TextImageRelationMapper.class);
        private final ParentImageReferenceSupport parentImageReferenceSupport = mock(ParentImageReferenceSupport.class);
        private final MediaSourcePathSupport mediaSourcePathSupport = mock(MediaSourcePathSupport.class);
        private final ImageService service;

        private TestFixture(int topK) {
            when(mediaSourcePathSupport.currentSourcePaths()).thenReturn(new MediaSourcePaths("", ""));
            service = new ImageService(
                    imageReferenceExtractor,
                    imageIngestionPipeline,
                    imageIndexService,
                    imageMetadataMapper,
                    textImageRelationMapper,
                    parentImageReferenceSupport,
                    mediaSourcePathSupport,
                    topK,
                    0.45d,
                    0.60d
            );
        }
    }
}
