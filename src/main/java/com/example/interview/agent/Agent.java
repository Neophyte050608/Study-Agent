package com.example.interview.agent;

public interface Agent<I, O> {
    O execute(I input);
}
