package io.github.imzmq.interview.routing.application.contract;

import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;

import java.util.concurrent.CompletionStage;

public interface UnderstandingService {

    CompletionStage<IntentRecord> understand(String sessionId, String userId, String rawQuery, String history);
}
