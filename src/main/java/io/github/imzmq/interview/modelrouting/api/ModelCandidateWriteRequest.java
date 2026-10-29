package io.github.imzmq.interview.modelrouting.api;

import lombok.Data;

@Data
public class ModelCandidateWriteRequest {

    private String name;
    private String displayName;
    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private boolean clearApiKey;
    private Integer priority;
    private Boolean isPrimary;
    private Boolean supportsThinking;
    private Boolean enabled;
    private String routeType;
}
