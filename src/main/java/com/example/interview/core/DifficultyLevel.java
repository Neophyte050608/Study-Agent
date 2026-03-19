package com.example.interview.core;

public enum DifficultyLevel {
    BASIC,
    INTERMEDIATE,
    ADVANCED;

    public DifficultyLevel harder() {
        if (this == BASIC) {
            return INTERMEDIATE;
        }
        if (this == INTERMEDIATE) {
            return ADVANCED;
        }
        return ADVANCED;
    }

    public DifficultyLevel easier() {
        if (this == ADVANCED) {
            return INTERMEDIATE;
        }
        if (this == INTERMEDIATE) {
            return BASIC;
        }
        return BASIC;
    }
}
