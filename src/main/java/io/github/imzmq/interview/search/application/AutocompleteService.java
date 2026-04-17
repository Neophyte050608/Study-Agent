package io.github.imzmq.interview.search.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.dto.search.AutocompleteItem;
import io.github.imzmq.interview.entity.search.AutocompleteDictDO;
import io.github.imzmq.interview.intent.domain.IntentTreeNode;
import io.github.imzmq.interview.mapper.search.AutocompleteDictMapper;
import io.github.imzmq.interview.routing.application.IntentTreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AutocompleteService {
    private static final Logger log = LoggerFactory.getLogger(AutocompleteService.class);
    private static final int TOP_K_SIZE = 50;
    private static final int ROUGH_RECALL_LIMIT = 50;

    private final AutocompleteDictMapper dictMapper;
    private final HeatTracker heatTracker;
    private final PersonalRanker personalRanker;
    private final IntentTreeService intentTreeService;

    private volatile RadixTreeEngine tree = new RadixTreeEngine();
    private volatile boolean ready = false;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public AutocompleteService(AutocompleteDictMapper dictMapper,
                               HeatTracker heatTracker,
                               PersonalRanker personalRanker,
                               IntentTreeService intentTreeService) {
        this.dictMapper = dictMapper;
        this.heatTracker = heatTracker;
        this.personalRanker = personalRanker;
        this.intentTreeService = intentTreeService;
    }

    @Async("profileUpdateExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        rebuildTreeFromDb();
        syncIntentExamples();
    }

    public List<AutocompleteItem> search(String prefix, String userId, int limit) {
        if (!ready || prefix == null || prefix.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        List<AutocompleteItem> candidates = tree.search(prefix, ROUGH_RECALL_LIMIT);
        return personalRanker.rerank(candidates, userId, prefix, limit);
    }

    public void recordClick(Long entryId) {
        heatTracker.recordClick(entryId);
    }

    @Async("profileUpdateExecutor")
    public void refreshTree() {
        if (!refreshing.compareAndSet(false, true)) {
            log.info("Autocomplete tree refresh already in progress, skipping");
            return;
        }
        try {
            rebuildTreeFromDb();
            syncIntentExamples();
        } finally {
            refreshing.set(false);
        }
    }

    private void rebuildTreeFromDb() {
        List<AutocompleteDictDO> records = dictMapper.selectList(new LambdaQueryWrapper<AutocompleteDictDO>()
                .eq(AutocompleteDictDO::getEnabled, true));

        RadixTreeEngine newTree = new RadixTreeEngine();
        int inserted = 0;
        for (AutocompleteDictDO record : records) {
            if (record.getPhrase() == null || record.getPhrase().isBlank()) {
                continue;
            }
            newTree.insertWithoutTopK(toItem(record), record.getPhrase().trim());
            inserted++;
        }
        newTree.rebuildTopK(TOP_K_SIZE);
        this.tree = newTree;
        this.ready = true;
        log.info("Autocomplete tree initialized with {} entries", inserted);
    }

    private void syncIntentExamples() {
        List<IntentTreeNode> leafIntents = intentTreeService.loadAllLeafIntents();

        List<AutocompleteDictDO> existingEntries = dictMapper.selectList(
                new LambdaQueryWrapper<AutocompleteDictDO>()
                        .eq(AutocompleteDictDO::getSource, "INTENT_EXAMPLE"));
        Set<String> existingKeys = existingEntries.stream()
                .map(entry -> buildSyncKey(entry.getPhrase(), entry.getIntentCode()))
                .collect(Collectors.toSet());

        List<AutocompleteDictDO> inserts = new ArrayList<>();
        for (IntentTreeNode leaf : leafIntents) {
            if (leaf.examples() == null || leaf.examples().isEmpty()) {
                continue;
            }
            for (String example : leaf.examples()) {
                if (example == null || example.isBlank()) {
                    continue;
                }
                String key = buildSyncKey(example.trim(), leaf.path());
                if (existingKeys.contains(key)) {
                    continue;
                }
                existingKeys.add(key);
                AutocompleteDictDO entry = new AutocompleteDictDO();
                entry.setPhrase(example.trim());
                entry.setIntentCode(leaf.path());
                entry.setCategory(mapIntentToCategory(leaf));
                entry.setSource("INTENT_EXAMPLE");
                entry.setGlobalHeat(0);
                entry.setEnabled(true);
                entry.setDeleted(false);
                inserts.add(entry);
            }
        }

        if (inserts.isEmpty()) {
            return;
        }
        for (AutocompleteDictDO entry : inserts) {
            dictMapper.insert(entry);
        }
        rebuildTreeFromDb();
        log.info("Synced {} autocomplete intent examples", inserts.size());
    }

    private AutocompleteItem toItem(AutocompleteDictDO record) {
        AutocompleteItem item = new AutocompleteItem();
        item.setId(record.getId());
        item.setPhrase(record.getPhrase());
        item.setCategory(record.getCategory());
        item.setScore(record.getGlobalHeat() == null ? 0.0 : record.getGlobalHeat());
        return item;
    }

    private String buildSyncKey(String phrase, String intentCode) {
        return (phrase == null ? "" : phrase) + "||" + (intentCode == null ? "" : intentCode);
    }

    private String mapIntentToCategory(IntentTreeNode leaf) {
        String path = leaf.path() == null ? "" : leaf.path();
        if (path.startsWith("INTERVIEW")) {
            return "面试";
        }
        if (path.startsWith("CODING")) {
            return "刷题";
        }
        if (path.startsWith("KNOWLEDGE")) {
            return "知识问答";
        }
        if (path.startsWith("PROFILE")) {
            return "学习计划";
        }
        return leaf.name();
    }
}






