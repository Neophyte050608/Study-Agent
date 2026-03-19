package com.example.interview.core;

public class Question {
    private String questionText;
    private String userAnswer;
    private int score;
    private int accuracy;
    private int logic;
    private int depth;
    private int boundary;
    private String deductions;
    private String citations;
    private String conflicts;
    private String feedback;

    public Question(String questionText, String userAnswer, int score, String feedback) {
        this(questionText, userAnswer, score, 0, 0, 0, 0, "", "", "", feedback);
    }

    public Question(String questionText, String userAnswer, int score, int accuracy, int logic, int depth, int boundary, String deductions, String citations, String conflicts, String feedback) {
        this.questionText = questionText;
        this.userAnswer = userAnswer;
        this.score = score;
        this.accuracy = accuracy;
        this.logic = logic;
        this.depth = depth;
        this.boundary = boundary;
        this.deductions = deductions == null ? "" : deductions;
        this.citations = citations == null ? "" : citations;
        this.conflicts = conflicts == null ? "" : conflicts;
        this.feedback = feedback;
    }

    public int getScore() { return score; }
    // Getters
    public String getQuestionText() { return questionText; }
    public String getUserAnswer() { return userAnswer; }
    public int getAccuracy() { return accuracy; }
    public int getLogic() { return logic; }
    public int getDepth() { return depth; }
    public int getBoundary() { return boundary; }
    public String getDeductions() { return deductions; }
    public String getCitations() { return citations; }
    public String getConflicts() { return conflicts; }
    public String getFeedback() { return feedback; }
}
