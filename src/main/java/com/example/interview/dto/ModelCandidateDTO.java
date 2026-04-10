package com.example.interview.dto;

import lombok.Data;

@Data
public class ModelCandidateDTO {

    private Long id;
    private String name;
    private String displayName;
    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private String apiKeyMasked;
    private Boolean apiKeyConfigured;
    private Boolean apiKeyReadable;
    private Boolean apiKeyCopyAllowed;
    private Integer priority;
    private Boolean isPrimary;
    private Boolean supportsThinking;
    private Boolean enabled;
    private String routeType;
}
