package io.github.imzmq.interview.search.application;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.github.imzmq.interview.entity.search.AutocompleteDictDO;
import io.github.imzmq.interview.mapper.search.AutocompleteDictMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HeatTracker {
    private static final Logger log = LoggerFactory.getLogger(HeatTracker.class);

    private volatile ConcurrentHashMap<Long, AtomicInteger> heatCounters = new ConcurrentHashMap<>();
    private final AutocompleteDictMapper dictMapper;

    public HeatTracker(AutocompleteDictMapper dictMapper) {
        this.dictMapper = dictMapper;
    }

    public void recordClick(Long entryId) {
        if (entryId == null) {
            return;
        }
        heatCounters.computeIfAbsent(entryId, key -> new AtomicInteger(0)).incrementAndGet();
    }

    @Scheduled(fixedDelay = 300_000)
    public void flushToDb() {
        if (heatCounters.isEmpty()) {
            return;
        }
        ConcurrentHashMap<Long, AtomicInteger> snapshot = heatCounters;
        heatCounters = new ConcurrentHashMap<>();

        int flushed = 0;
        for (Map.Entry<Long, AtomicInteger> entry : snapshot.entrySet()) {
            int delta = entry.getValue().get();
            if (delta <= 0) {
                continue;
            }
            try {
                dictMapper.update(null, new LambdaUpdateWrapper<AutocompleteDictDO>()
                        .eq(AutocompleteDictDO::getId, entry.getKey())
                        .setSql("global_heat = global_heat + " + delta));
                flushed++;
            } catch (Exception ex) {
                log.warn("Failed to flush heat for entry {}: {}", entry.getKey(), ex.getMessage());
                heatCounters.computeIfAbsent(entry.getKey(), key -> new AtomicInteger(0)).addAndGet(delta);
            }
        }
        if (flushed > 0) {
            log.info("Flushed heat for {} entries", flushed);
        }
    }
}



