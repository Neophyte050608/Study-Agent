package com.example.interview.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ImageVectorStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(ImageVectorStoreConfig.class);

    private final MilvusServiceClient milvusClient;
    private final String collectionName;

    public ImageVectorStoreConfig(
            MilvusServiceClient milvusClient,
            @Value("${app.multimodal.milvus.image-collection:interview_images}") String collectionName) {
        this.milvusClient = milvusClient;
        this.collectionName = collectionName;
    }

    @PostConstruct
    public void initializeImageCollection() {
        try {
            R<Boolean> hasCollection = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );
            if (hasCollection.getException() != null) {
                logger.warn("Check image collection '{}' failed: {}", collectionName, hasCollection.getException().getMessage());
                return;
            }
            if (Boolean.TRUE.equals(hasCollection.getData())) {
                logger.info("Milvus image collection '{}' already exists", collectionName);
                loadCollection();
                return;
            }

            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .addFieldType(FieldType.newBuilder()
                            .withName("image_id")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(128)
                            .withPrimaryKey(true)
                            .withAutoID(false)
                            .build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("text_embedding")
                            .withDataType(DataType.FloatVector)
                            .withDimension(2048)
                            .build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("visual_embedding")
                            .withDataType(DataType.FloatVector)
                            .withDimension(512)
                            .build())
                    .addFieldType(FieldType.newBuilder()
                            .withName("metadata")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(4096)
                            .build())
                    .build();

            R<RpcStatus> createResult = milvusClient.createCollection(createParam);
            if (createResult.getException() != null) {
                logger.warn("Create image collection '{}' failed: {}", collectionName, createResult.getException().getMessage());
                return;
            }

            createIndex("text_embedding", "image_text_embedding_idx");
            createIndex("visual_embedding", "image_visual_embedding_idx");
            loadCollection();
            logger.info("Initialized Milvus image collection '{}'", collectionName);
        } catch (Exception e) {
            logger.warn("Initialize Milvus image collection '{}' failed", collectionName, e);
        }
    }

    private void createIndex(String fieldName, String indexName) {
        R<RpcStatus> response = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName(fieldName)
                        .withIndexName(indexName)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.COSINE)
                        .withExtraParam("{\"nlist\":128}")
                        .build()
        );
        if (response.getException() != null) {
            logger.warn("Create Milvus index '{}' on '{}' failed: {}", indexName, fieldName, response.getException().getMessage());
        }
    }

    private void loadCollection() {
        R<RpcStatus> loadResult = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        if (loadResult.getException() != null) {
            logger.warn("Load image collection '{}' failed: {}", collectionName, loadResult.getException().getMessage());
            return;
        }
        logger.info("Loaded Milvus image collection '{}'", collectionName);
    }
}
