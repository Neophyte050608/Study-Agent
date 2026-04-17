package io.github.imzmq.interview.modelrouting.provider;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;

import java.util.List;

public interface CandidateProvider {

    List<ModelRoutingCandidate> getCandidates();
}



