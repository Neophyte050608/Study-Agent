package io.github.imzmq.interview.routing.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.imzmq.interview.common.StringUtils;
import io.github.imzmq.interview.config.routing.IntentTreeProperties;
import io.github.imzmq.interview.entity.intent.IntentSlotRefineCaseDO;
import io.github.imzmq.interview.mapper.intent.IntentSlotRefineCaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IntentSlotRefineCaseService {

    private final IntentSlotRefineCaseMapper mapper;

    public IntentSlotRefineCaseService(IntentSlotRefineCaseMapper mapper) {
        this.mapper = mapper;
    }

    public List<IntentTreeProperties.SlotRefineCase> listEnabled() {
        List<IntentSlotRefineCaseDO> rows = mapper.selectList(
                Wrappers.<IntentSlotRefineCaseDO>lambdaQuery()
                        .eq(IntentSlotRefineCaseDO::getEnabled, true)
                        .orderByAsc(IntentSlotRefineCaseDO::getSortOrder, IntentSlotRefineCaseDO::getId)
        );
        List<IntentTreeProperties.SlotRefineCase> result = new ArrayList<>();
        for (IntentSlotRefineCaseDO row : rows) {
            IntentTreeProperties.SlotRefineCase item = new IntentTreeProperties.SlotRefineCase();
            item.setTaskType(StringUtils.trimToEmpty(row.getTaskType()));
            item.setUserQuery(StringUtils.trimToEmpty(row.getUserQuery()));
            item.setAiOutput(StringUtils.trimToEmpty(row.getAiOutput()));
            if (!item.getUserQuery().isBlank() && !item.getAiOutput().isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    public long countEnabled() {
        return mapper.selectCount(
                Wrappers.<IntentSlotRefineCaseDO>lambdaQuery()
                        .eq(IntentSlotRefineCaseDO::getEnabled, true)
        );
    }

    public List<Map<String, String>> listEnabledByTaskType(String taskType) {
        String normalizedTaskType = StringUtils.trimToEmpty(taskType).toUpperCase();
        List<Map<String, String>> result = new ArrayList<>();
        for (IntentTreeProperties.SlotRefineCase item : listEnabled()) {
            String caseTaskType = StringUtils.trimToEmpty(item.getTaskType()).toUpperCase();
            if (!caseTaskType.isBlank() && !caseTaskType.equals(normalizedTaskType)) {
                continue;
            }
            Map<String, String> mapped = new LinkedHashMap<>();
            mapped.put("user_query", StringUtils.trimToEmpty(item.getUserQuery()));
            mapped.put("ai_response", StringUtils.trimToEmpty(item.getAiOutput()));
            result.add(mapped);
        }
        return result;
    }

    @Transactional
    public void replaceAll(List<IntentTreeProperties.SlotRefineCase> cases) {
        mapper.update(
                null,
                Wrappers.<IntentSlotRefineCaseDO>lambdaUpdate()
                        .set(IntentSlotRefineCaseDO::getDeleted, true)
                        .eq(IntentSlotRefineCaseDO::getDeleted, false)
        );
        if (cases == null || cases.isEmpty()) {
            return;
        }
        int index = 1;
        for (IntentTreeProperties.SlotRefineCase item : cases) {
            if (item == null) {
                continue;
            }
            String userQuery = StringUtils.trimToEmpty(item.getUserQuery());
            String aiOutput = StringUtils.trimToEmpty(item.getAiOutput());
            if (userQuery.isBlank() || aiOutput.isBlank()) {
                continue;
            }
            IntentSlotRefineCaseDO row = new IntentSlotRefineCaseDO();
            row.setTaskType(StringUtils.trimToEmpty(item.getTaskType()));
            row.setUserQuery(userQuery);
            row.setAiOutput(aiOutput);
            row.setSortOrder(index++);
            row.setEnabled(true);
            row.setDeleted(false);
            mapper.insert(row);
        }
    }
}
