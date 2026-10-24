package io.github.imzmq.interview.chat.application.contract.extension;

import io.github.imzmq.interview.chat.application.contract.TaskResponsePresentationOperations.PresentationChannel;

/** Business-owned formatter for a task result shape not owned by chat. */
public interface ChatTaskResultPresenter {
    boolean supports(Object result);

    String format(Object result, PresentationChannel channel);
}
