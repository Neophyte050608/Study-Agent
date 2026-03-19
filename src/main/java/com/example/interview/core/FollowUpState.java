package com.example.interview.core;

public enum FollowUpState {
    REMEDIATE,
    PROBE,
    ADVANCE;

    public static FollowUpState byScore(int score) {
        if (score < 60) {
            return REMEDIATE;
        }
        if (score >= 85) {
            return ADVANCE;
        }
        return PROBE;
    }
}
