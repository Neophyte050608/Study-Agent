package com.example.interview.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ImageEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(ImageEmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final RestTemplate restTemplate;
    private final int visualDimension;
    private final boolean visualEmbeddingEnabled;
    private final String clipServiceUrl;

    public ImageEmbeddingService(EmbeddingModel embeddingModel,
                                 @org.springframework.beans.factory.annotation.Value("${app.multimodal.clip.service-url:http://localhost:8200}") String clipServiceUrl,
                                 @org.springframework.beans.factory.annotation.Value("${app.multimodal.visual.embedding-dimension:512}") int visualDimension,
                                 @org.springframework.beans.factory.annotation.Value("${app.image.visual-embedding.enabled:false}") boolean visualEmbeddingEnabled) {
        this.embeddingModel = embeddingModel;
        this.restTemplate = createRestTemplate();
        this.clipServiceUrl = clipServiceUrl == null ? "http://localhost:8200" : clipServiceUrl.trim();
        this.visualDimension = visualDimension;
        this.visualEmbeddingEnabled = visualEmbeddingEnabled;
    }

    public ImageEmbedding embed(String summaryText, Path imagePath) {
        List<Double> textVector = embedText(summaryText == null ? "" : summaryText);
        List<Double> visualVector = embedVisual(imagePath);
        return new ImageEmbedding(textVector, visualVector);
    }

    private List<Double> embedText(String summaryText) {
        try {
            float[] output = embeddingModel.embed(new Document(summaryText));
            List<Double> vector = new ArrayList<>(output.length);
            for (float value : output) {
                vector.add((double) value);
            }
            return vector;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Double> embedVisual(Path imagePath) {
        if (!visualEmbeddingEnabled || imagePath == null) {
            return List.of();
        }
        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String mimeType = Files.probeContentType(imagePath);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "image/png";
            }
            ClipResponse response = restTemplate.postForObject(
                    clipServiceUrl + "/embed",
                    new ClipImageRequest(base64Image, mimeType),
                    ClipResponse.class
            );
            if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
                return List.of();
            }
            return response.embedding();
        } catch (Exception e) {
            logger.warn("CLIP image embedding failed for {}, returning empty vector: {}", imagePath, e.getMessage());
            return List.of();
        }
    }

    public List<Double> embedVisualText(String text) {
        if (!visualEmbeddingEnabled) {
            return List.of();
        }
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        try {
            ClipResponse response = restTemplate.postForObject(
                    clipServiceUrl + "/embed-text",
                    new ClipTextRequest(normalized),
                    ClipResponse.class
            );
            if (response == null || response.embedding() == null || response.embedding().isEmpty()) {
                return List.of();
            }
            return response.embedding();
        } catch (Exception e) {
            logger.warn("CLIP text embedding failed, returning empty vector: {}", e.getMessage());
            return List.of();
        }
    }

    public boolean isVisualEmbeddingEnabled() {
        return visualEmbeddingEnabled;
    }

    public int visualDimension() {
        return visualDimension;
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(30000);
        return new RestTemplate(requestFactory);
    }

    public record ImageEmbedding(List<Double> textEmbedding, List<Double> visualEmbedding) {
    }

    private record ClipImageRequest(String image_base64, String mime_type) {
    }

    private record ClipTextRequest(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClipResponse(List<Double> embedding, int dimension) {
    }
}
