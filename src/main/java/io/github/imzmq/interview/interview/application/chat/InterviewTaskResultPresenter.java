package io.github.imzmq.interview.interview.application.chat;

import io.github.imzmq.interview.interview.application.contract.result.InterviewAnswerResult;
import io.github.imzmq.interview.interview.application.contract.result.InterviewFinalReport;

import io.github.imzmq.interview.interview.application.agent.InterviewOrchestratorAgent;
import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.PresentationChannel;
import io.github.imzmq.interview.chat.application.contract.extension.ChatTaskResultPresenter;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import org.springframework.stereotype.Component;

@Component
public class InterviewTaskResultPresenter implements ChatTaskResultPresenter {

    @Override
    public boolean supports(Object result) {
        return result instanceof InterviewSession
                || result instanceof InterviewAnswerResult
                || result instanceof InterviewFinalReport;
    }

    @Override
    public String format(Object result, PresentationChannel channel) {
        if (result instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }
        if (result instanceof InterviewAnswerResult answer) {
            return formatAnswer(answer, channel);
        }
        if (result instanceof InterviewFinalReport report) {
            return formatReport(report, channel);
        }
        throw new IllegalArgumentException("Unsupported interview result: " + result);
    }

    private String formatAnswer(InterviewAnswerResult result, PresentationChannel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("【评分】").append(result.score()).append("\n\n");
        sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
        if (result.finished()) {
            if (channel == PresentationChannel.IM) {
                sb.append("恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");
            } else {
                sb.append("本次模拟面试已结束。你可以说\"生成报告\"查看最终评估。");
            }
        } else {
            sb.append("【下一题】\n").append(result.nextQuestion());
        }
        return sb.toString();
    }

    private String formatReport(InterviewFinalReport report, PresentationChannel channel) {
        StringBuilder sb = new StringBuilder();
        sb.append("====== 面试复盘报告 ======\n\n");
        sb.append("【总评】\n").append(report.summary()).append("\n\n");
        sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
        sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
        if (channel == PresentationChannel.IM) {
            sb.append("【平均分】").append(String.format("%.1f", report.averageScore())).append("\n\n");
        }
        sb.append("【后续建议】\n").append(report.nextFocus());
        return sb.toString();
    }
}
