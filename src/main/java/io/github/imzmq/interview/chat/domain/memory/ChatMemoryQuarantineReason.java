package io.github.imzmq.interview.chat.domain.memory;

public enum ChatMemoryQuarantineReason {
    AMBIGUOUS_BATCH_ORDER,
    MULTIPLE_ACTIVE_SINGLETONS,
    SUBJECT_CARDINALITY_CONFLICT,
    OLDER_THAN_CURRENT
}
