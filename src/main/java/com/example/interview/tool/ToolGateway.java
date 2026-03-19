package com.example.interview.tool;

public interface ToolGateway<I, O> {
    O run(I input);
}
