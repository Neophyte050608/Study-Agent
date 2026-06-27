package io.github.imzmq.interview.model.provider;

import io.github.imzmq.interview.model.core.ModelRoutingCandidate;

import java.util.List;

public interface CandidateProvider {

    List<ModelRoutingCandidate> getCandidates();
}
