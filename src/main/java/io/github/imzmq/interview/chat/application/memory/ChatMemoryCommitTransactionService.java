package io.github.imzmq.interview.chat.application.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryCandidate;
import io.github.imzmq.interview.chat.domain.memory.ChatMemoryNormalizer;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryExtractionTaskMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemoryItemMapper;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceDO;
import io.github.imzmq.interview.chat.infrastructure.persistence.memory.ChatMemorySourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ChatMemoryCommitTransactionService {

    private final ChatMemoryExtractionTaskMapper taskMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMemoryItemMapper itemMapper;
    private final ChatMemorySourceMapper sourceMapper;
    private final ChatMemoryOutboxEventService outboxEventService;

    public ChatMemoryCommitTransactionService(ChatMemoryExtractionTaskMapper taskMapper,
                                   ChatSessionMapper sessionMapper,
                                   ChatMessageMapper messageMapper,
                                   ChatMemoryItemMapper itemMapper,
                                   ChatMemorySourceMapper sourceMapper,
                                   ChatMemoryOutboxEventService outboxEventService) {
        this.taskMapper = taskMapper;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.outboxEventService = outboxEventService;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void commitOnce(String taskId, String leaseToken, List<ChatMemoryCandidate> candidates) {
        commitBatchRequests(List.of(new ChatMemoryCommitRequest(taskId, leaseToken, candidates)));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
        LocalDateTime now = LocalDateTime.now();
        List<ClaimedTaskCommit> claimedTasks = taskCommits.stream()
                .map(taskCommit -> claimAndValidateSource(taskCommit, now))
                .toList();

        List<CandidateCommit> candidateCommits = claimedTasks.stream()
                .flatMap(claimed -> claimed.request().candidates().stream()
                        .map(candidate -> validateCandidate(claimed, candidate)))
                .sorted(Comparator.comparing(CandidateCommit::contentHash)
                        .thenComparing(commit -> commit.task().getTaskId())
                        .thenComparingInt(commit -> commit.candidate().source().start())
                        .thenComparingInt(commit -> commit.candidate().source().end()))
                .toList();
        candidateCommits.forEach(commit ->
                commitCandidate(commit.task(), commit.candidate(), now));

        for (ClaimedTaskCommit claimed : claimedTasks) {
            if (taskMapper.markTaskDone(claimed.task().getTaskId(),
                    claimed.request().leaseToken(), now) != 1) {
                throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
            }
        }
    }

    private TaskCommit loadTaskIdentity(ChatMemoryCommitRequest request) {
        ChatMemoryExtractionTaskDO identity = taskMapper.selectTaskIdentity(request.taskId());
        if (identity == null) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
        return new TaskCommit(request, identity);
    }

    private void assertCompatibleBatch(List<TaskCommit> taskCommits) {
        ChatMemoryExtractionTaskDO first = taskCommits.getFirst().identity();
        boolean incompatible = taskCommits.stream().anyMatch(taskCommit ->
                !java.util.Objects.equals(first.getUserId(), taskCommit.identity().getUserId())
                        || !java.util.Objects.equals(first.getSessionId(),
                        taskCommit.identity().getSessionId()));
        long distinctTasks = taskCommits.stream()
                .map(taskCommit -> taskCommit.identity().getTaskId())
                .distinct()
                .count();
        if (incompatible || distinctTasks != taskCommits.size()) {
            throw new ChatMemoryCommitException("MEMORY_TASK_BATCH_INCOMPATIBLE");
        }
    }

    private ClaimedTaskCommit claimAndValidateSource(TaskCommit taskCommit, LocalDateTime now) {
        ChatMemoryExtractionTaskDO identity = taskCommit.identity();
        ChatMemoryExtractionTaskDO task = taskMapper.lockOwnedTask(
                identity.getTaskId(), taskCommit.request().leaseToken(), now);
        if (task == null || !sameOwner(identity, task)) {
            throw new ChatMemoryCommitException("MEMORY_LEASE_LOST");
        }
        ChatMessageDO sourceMessage = lockActiveSourceMessage(task);
        if (!"user".equals(sourceMessage.getRole())) {
            throw new ChatMemoryCommitException("MEMORY_SOURCE_INVALID");
        }
        return new ClaimedTaskCommit(taskCommit.request(), task, sourceMessage);
    }

    private CandidateCommit validateCandidate(ClaimedTaskCommit claimed,
                                              ChatMemoryCandidate candidate) {
        validateCandidateSource(claimed.task(), claimed.sourceMessage(), candidate);
        return new CandidateCommit(
                claimed.task(), candidate,
                ChatMemoryNormalizer.contentHash(candidate.category(), candidate.content()));
    }

    private boolean sameOwner(ChatMemoryExtractionTaskDO expected,
                              ChatMemoryExtractionTaskDO actual) {
        return java.util.Objects.equals(expected.getUserId(), actual.getUserId())
                && java.util.Objects.equals(expected.getSessionId(), actual.getSessionId())
                && java.util.Objects.equals(expected.getSourceMessageId(), actual.getSourceMessageId())
                && java.util.Objects.equals(expected.getSourceMessagePk(), actual.getSourceMessagePk());
    }

    private ChatSessionDO lockActiveSession(ChatMemoryExtractionTaskDO task) {
        ChatSessionDO session = sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSessionDO>()
                        .eq(ChatSessionDO::getSessionId, task.getSessionId())
                        .eq(ChatSessionDO::getUserId, task.getUserId())
                        .last("LIMIT 1 FOR UPDATE"));
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            throw new ChatMemoryCommitException("MEMORY_SESSION_INVALID");
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

    private void validateCandidateSource(ChatMemoryExtractionTaskDO task,
                                         ChatMessageDO message,
                                         ChatMemoryCandidate candidate) {
        if (!task.getSourceMessageId().equals(candidate.source().sourceMessageId())
                || !candidate.source().matches(message.getContent())) {
            throw new ChatMemoryCommitException("MEMORY_SOURCE_INVALID");
        }
    }

    private void commitCandidate(ChatMemoryExtractionTaskDO task,
                                 ChatMemoryCandidate candidate,
                                 LocalDateTime now) {
        String contentHash = ChatMemoryNormalizer.contentHash(candidate.category(), candidate.content());
        ChatMemoryItemDO item = itemMapper.selectOne(
                new LambdaQueryWrapper<ChatMemoryItemDO>()
                        .eq(ChatMemoryItemDO::getUserId, task.getUserId())
                        .eq(ChatMemoryItemDO::getCategory, candidate.category().name())
                        .eq(ChatMemoryItemDO::getContentHash, contentHash)
                        .last("LIMIT 1 FOR UPDATE"));

        if (item != null && !"ACTIVE".equals(item.getStatus())) {
            return;
        }
        if (item == null) {
            item = new ChatMemoryItemDO();
            item.setUserId(task.getUserId());
            item.setCategory(candidate.category().name());
            item.setContent(candidate.content());
            item.setContentHash(contentHash);
            item.setStatus("ACTIVE");
            item.setVersion(1L);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            itemMapper.insert(item);
            insertSource(item.getId(), task, candidate, now);
            outboxEventService.enqueue(
                    item.getId(), task.getUserId(), item.getVersion(), "UPSERT");
            return;
        }

        ChatMemorySourceDO existingSource = sourceMapper.selectOne(
                new LambdaQueryWrapper<ChatMemorySourceDO>()
                        .eq(ChatMemorySourceDO::getMemoryId, item.getId())
                        .eq(ChatMemorySourceDO::getSourceMessageId, task.getSourceMessageId())
                        .eq(ChatMemorySourceDO::getSourceStart, candidate.source().start())
                        .eq(ChatMemorySourceDO::getSourceEnd, candidate.source().end())
                        .last("LIMIT 1 FOR UPDATE"));
        if (existingSource != null && existingSource.getInvalidatedAt() == null) {
            return;
        }
        if (existingSource == null) {
            insertSource(item.getId(), task, candidate, now);
        } else {
            existingSource.setInvalidatedAt(null);
            sourceMapper.updateById(existingSource);
        }

        long currentVersion = item.getVersion();
        int updated = itemMapper.update(null,
                new LambdaUpdateWrapper<ChatMemoryItemDO>()
                        .eq(ChatMemoryItemDO::getId, item.getId())
                        .eq(ChatMemoryItemDO::getVersion, currentVersion)
                        .set(ChatMemoryItemDO::getVersion, currentVersion + 1)
                        .set(ChatMemoryItemDO::getUpdatedAt, now));
        if (updated != 1) {
            throw new ChatMemoryCommitException("MEMORY_VERSION_CONFLICT");
        }
        outboxEventService.enqueue(
                item.getId(), task.getUserId(), currentVersion + 1, "UPSERT");
    }

    private void insertSource(Long memoryId,
                              ChatMemoryExtractionTaskDO task,
                              ChatMemoryCandidate candidate,
                              LocalDateTime now) {
        ChatMemorySourceDO source = new ChatMemorySourceDO();
        source.setMemoryId(memoryId);
        source.setUserId(task.getUserId());
        source.setSessionId(task.getSessionId());
        source.setSourceMessageId(task.getSourceMessageId());
        source.setSourceMessagePk(task.getSourceMessagePk());
        source.setSourceStart(candidate.source().start());
        source.setSourceEnd(candidate.source().end());
        source.setCreatedAt(now);
        sourceMapper.insert(source);
    }

    private record TaskCommit(
            ChatMemoryCommitRequest request,
            ChatMemoryExtractionTaskDO identity
    ) {
    }

    private record ClaimedTaskCommit(
            ChatMemoryCommitRequest request,
            ChatMemoryExtractionTaskDO task,
            ChatMessageDO sourceMessage
    ) {
    }

    private record CandidateCommit(
            ChatMemoryExtractionTaskDO task,
            ChatMemoryCandidate candidate,
            String contentHash
    ) {
    }

}
