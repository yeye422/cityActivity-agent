package com.city.service.memory;

import com.city.enums.PreferencePolarity;
import com.city.exception.CityException;
import com.city.mapper.PreferenceFactMapper;
import com.city.model.PreferenceFact;
import com.city.model.PreferenceFactRequest;
import com.city.service.slot.SlotOptionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 长期偏好的显式写入、召回和删除边界。 */
@Service
public class PreferenceMemoryService {
    private final PreferenceFactMapper mapper;
    private final SlotOptionService slotOptionService;

    public PreferenceMemoryService(PreferenceFactMapper mapper, SlotOptionService slotOptionService) {
        this.mapper = mapper;
        this.slotOptionService = slotOptionService;
    }

    public List<PreferenceFact> findActive(Long userId) {
        requireUser(userId);
        return mapper.findActiveByUserId(userId);
    }

    public PreferenceFact remember(Long userId, PreferenceFactRequest request) {
        requireUser(userId);
        if (request == null || request.slotName() == null || request.slotValue() == null
                || request.polarity() == null) {
            throw new CityException("偏好字段不完整");
        }
        String slotName = request.slotName().trim();
        String slotValue = request.slotValue().trim();
        if (!SlotOptionService.SLOT_NAMES.contains(slotName)) {
            throw new CityException("不支持的偏好槽位: " + slotName);
        }
        Map<String, List<String>> options = slotOptionService.findAllOptions();
        if (!options.getOrDefault(slotName, List.of()).contains(slotValue)) {
            throw new CityException("非法槽位标签: " + slotName + "=" + slotValue);
        }
        String source = request.source() == null || request.source().isBlank()
                ? "EXPLICIT" : request.source().trim();
        mapper.upsert(userId, slotName, slotValue, request.polarity(), source);
        return mapper.findByNaturalKey(userId, slotName, slotValue, request.polarity());
    }

    public void forget(Long userId, Long id, Integer expectedVersion) {
        requireUser(userId);
        if (id == null || expectedVersion == null || expectedVersion < 1) {
            throw new CityException("删除偏好需要 id 和有效 version");
        }
        if (mapper.softDelete(userId, id, expectedVersion) == 0) {
            throw new CityException("偏好不存在、已删除或版本已更新");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new CityException("用户 ID 不合法");
        }
    }
}
