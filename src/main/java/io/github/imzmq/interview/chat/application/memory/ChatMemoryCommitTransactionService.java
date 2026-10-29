package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryNormalizer;
import io.github.imzmq.interview.chat.domain.memory.ChatMemorySubjectPolicy;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ChatMemoryCommitTransactionService {

    private final ChatMemoryExtractionTaskMapper taskMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMemoryLifecycleWriter lifecycleWriter;

    public ChatMemoryCommitTransactionService(
            ChatMemoryExtractionTaskMapper taskMapper,
            ChatSessionMapper sessionMapper,
            ChatMessageMapper messageMapper,
            ChatMemoryLifecycleWriter lifecycleWriter) {
        this.taskMapper = taskMapper;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.lifecycleWriter = lifecycleWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitOnce(String taskId, String leaseToken, List<ChatMemoryCandidate> candidates) {
        commitBatchRequests(List.of(new ChatMemoryCommitRequest(taskId, leaseToken, candidates)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitBatchOnce(List<ChatMemoryCommitRequest> requests) {
        commitBatchRequests(requests);
    }

    private void commitBatchRequests(List<ChatMemoryCommitRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        List<TaskCommit> taskCommits = requests.stream()
                .map(this::loadTaskIdentity)
                .sorted(Comparator.comparing(taskCommit -> taskCommit.identity().getTaskId()))
                .toList();
        assertCompatibleBatch(taskCommits);

        lockActiveSession(taskCommits.getFirst().identity());
        LocalDateTime writeTime = LocalDateTime.now();
        List<ClaimedTaskCommit> claimedTasks = taskCommits.stream()
                .map(taskCommit -> claimAndValidateSource(taskCommit, writeTime))
                .toList();

        List<ChatMemoryLifecycleRequest> lifecycleRequests = claimedTasks.stream()
                .flatMap(claimed -> claimed.request().candidates().stream()
                        .map(candidate -> lifecycleRequest(claimed, candidate)))
                .sorted(Comparator.comparing(ChatMemoryLifecycleRequest::contentHash)
                        .thenComparing(request -> request.candidate().source().sourceMessageId())
                        .thenComparingInt(ChatMemoryLifecycleRequest::originQuoteStart)
                        .thenComparingInt(request -> request.candidate().source().end()))
                .toList();
        lifecycleWriter.writeBatch(lifecycleRequests, writeTime);

        for (ClaimedTaskCommit claimed : claimedTasks) {
            if (taskMapper.markTaskDone(
                    claimed.task().getTaskId(), claimed.request().leaseToken(), writeTime) != 1) {
                throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
            }
        }
    }

    private TaskCommit loadTaskIdentity(ChatMemoryCommitRequest request) {
        if (request == null || request.taskId() == null || request.taskId().isBlank()
                || request.leaseToken() == null || request.leaseToken().isBlank()
                || request.candidates() == null) {
            throw new ChatMemoryCommitException("MEMORY_TASK_BATCH_INCOMPATIBLE");
        }
        ChatMemoryExtractionTaskDO identity = taskMapper.selectTaskIdentity(request.taskId());
        if (identity == null) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
        return new TaskCommit(request, identity);
    }

    private void assertCompatibleBatch(List<TaskCommit> taskCommits) {
        ChatMemoryExtractionTaskDO first = taskCommits.getFirst().identity();
        boolean incompatible = taskCommits.stream().anyMatch(taskCommit ->
                !Objects.equals(first.getUserId(), taskCommit.identity().getUserId())
                        || !Objects.equals(first.getSessionId(), taskCommit.identity().getSessionId()));
        long distinctTasks = taskCommits.stream()
                .map(taskCommit -> taskCommit.identity().getTaskId())
                .distinct()
                .count();
        if (incompatible || distinctTasks != taskCommits.size()) {
            throw new ChatMemoryCommitException("MEMORY_TASK_BATCH_INCOMPATIBLE");
        }
    }

    private ClaimedTaskCommit claimAndValidateSource(
            TaskCommit taskCommit,
            LocalDateTime writeTime) {
        ChatMemoryExtractionTaskDO identity = taskCommit.identity();
        ChatMemoryExtractionTaskDO task = taskMapper.lockOwnedTask(
                identity.getTaskId(), taskCommit.request().leaseToken(), writeTime);
        if (task == null || !sameOwner(identity, task)) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
        ChatMessageDO sourceMessage = lockActiveSourceMessage(task);
        if (!"user".equals(sourceMessage.getRole()) || sourceMessage.getCreatedAt() == null) {
            throw new ChatMemoryCommitException("MEMORY_SOURCE_INVALID");
        }
        return new ClaimedTaskCommit(taskCommit.request(), task, sourceMessage);
    }

    private ChatMemoryLifecycleRequest lifecycleRequest(
            ClaimedTaskCommit claimed,
            ChatMemoryCandidate candidate) {
        validateCandidateSource(claimed.task(), claimed.sourceMessage(), candidate);
        return new ChatMemoryLifecycleRequest(
                claimed.task().getUserId(),
                claimed.task().getSessionId(),
                candidate,
                ChatMemoryNormalizer.contentHash(candidate.category(), candidate.content()),
                ChatMemorySubjectPolicy.cardinality(candidate.category(), candidate.subjectKey()),
                claimed.sourceMessage().getCreatedAt(),
                claimed.sourceMessage().getId(),
                candidate.source().start(),
                false);
    }

    private boolean sameOwner(
            ChatMemoryExtractionTaskDO expected,
            ChatMemoryExtractionTaskDO actual) {
        return Objects.equals(expected.getUserId(), actual.getUserId())
                && Objects.equals(expected.getSessionId(), actual.getSessionId())
                && Objects.equals(expected.getSourceMessageId(), actual.getSourceMessageId())
                && Objects.equals(expected.getSourceMessagePk(), actual.getSourceMessagePk());
    }

    private ChatSessionDO lockActiveSession(ChatMemoryExtractionTaskDO task) {
        ChatSessionDO session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, task.getSessionId())
                        .eq(ChatSessionDO::getUserId, task.getUserId())
                        .last("LIMIT 1 FOR UPDATE"));
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            throw new ChatMemoryCommitException("MEMORY_SESSION_DELETED");
        }
        return session;
    }

    private ChatMessageDO lockActiveSourceMessage(ChatMemoryExtractionTaskDO task) {
        ChatMessageDO message = messageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageDO>()
                        .eq(ChatMessageDO::getId, task.getSourceMessagePk())
                        .eq(ChatMessageDO::getMessageId, task.getSourceMessageId())
                        .eq(ChatMessageDO::getSessionId, task.getSessionId())
                        .last("LIMIT 1 FOR UPDATE"));
        if (message == null || Boolean.TRUE.equals(message.getDeleted())) {
            throw new ChatMemoryCommitException("MEMORY_SOURCE_INVALID");
        }
        return message;
    }

    private void validateCandidateSource(
            ChatMemoryExtractionTaskDO task,
            ChatMessageDO message,
            ChatMemoryCandidate candidate) {
        if (candidate == null
                || !task.getSourceMessageId().equals(candidate.source().sourceMessageId())
                || !candidate.source().matches(message.getContent())) {
            throw new ChatMemoryCommitException("MEMORY_SOURCE_INVALID");
        }
    }

    private record TaskCommit(
            ChatMemoryCommitRequest request,
            ChatMemoryExtractionTaskDO identity) {
    }

    private record ClaimedTaskCommit(
            ChatMemoryCommitRequest request,
            ChatMemoryExtractionTaskDO task,
            ChatMessageDO sourceMessage) {
    }
}
