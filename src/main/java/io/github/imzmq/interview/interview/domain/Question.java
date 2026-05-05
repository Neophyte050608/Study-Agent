package io.github.imzmq.interview.interview.domain;

/**
 * 面试题目及回答实体类。
 * 记录了单道题目的问题文本、用户回答、多维度评分以及反馈建议。
 */
public class Question {
    /** 问题文本 */
    private String questionText;
    /** 用户的回答文本 */
    private String userAnswer;
    /** 综合得分 (0-100) */
    private int score;
    /** 准确性评分 (0-100) */
    private int accuracy;
    /** 逻辑性评分 (0-100) */
    private int logic;
    /** 深度评分 (0-100) */
    private int depth;
    /** 边界处理评分 (0-100) */
    private int boundary;
    /** 扣分项说明 */
    private String deductions;
    /** 引用的知识库来源 */
    private String citations;
    /** 与知识库冲突的点 */
    private String conflicts;
    /** 详细反馈与改进建议 */
    private String feedback;

    public Question() {
        // 默认构造函数，供 Jackson 反序列化使用
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
    // Getters and Setters
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getUserAnswer() { return userAnswer; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }
    public void setScore(int score) { this.score = score; }
    public int getAccuracy() { return accuracy; }
    public void setAccuracy(int accuracy) { this.accuracy = accuracy; }
    public int getLogic() { return logic; }
    public void setLogic(int logic) { this.logic = logic; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public int getBoundary() { return boundary; }
    public void setBoundary(int boundary) { this.boundary = boundary; }
    public String getDeductions() { return deductions; }
    public void setDeductions(String deductions) { this.deductions = deductions; }
    public String getCitations() { return citations; }
    public void setCitations(String citations) { this.citations = citations; }
    public String getConflicts() { return conflicts; }
    public void setConflicts(String conflicts) { this.conflicts = conflicts; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
}




