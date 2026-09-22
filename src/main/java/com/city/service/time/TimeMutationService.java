package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 将 IntentAgent 的时间补丁应用到历史 TimeConstraint。
 * LLM 只表达本轮 KEEP / SET / CLEAR；会话状态修改始终由 Java 确定性执行。
 */
@Service
public class TimeMutationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TemporalValidator temporalValidator;

    public TimeMutationService(TemporalValidator temporalValidator) {
        this.temporalValidator = temporalValidator;
    }

    public TimeConstraint apply(TimeConstraint historical, TemporalMutation mutation) {
        TimeConstraint base = historical == null ? TimeConstraint.empty() : historical;
        if (!temporalValidator.isValid(mutation)) {
            return base;
        }

        LocalDate dateStart = switch (mutation.dateMode()) {
            case KEEP -> base.dateStart();
            case SET -> mutation.dateStart();
            case CLEAR -> null;
        };
        LocalDate dateEnd = switch (mutation.dateMode()) {
            case KEEP -> base.dateEnd();
            case SET -> mutation.dateEnd();
            case CLEAR -> null;
        };

        LocalTime timeStart = switch (mutation.timeMode()) {
            case KEEP -> base.startTime();
            case SET -> mutation.timeStart();
            case CLEAR -> null;
        };
        LocalTime timeEnd = switch (mutation.timeMode()) {
            case KEEP -> base.endTime();
            case SET -> mutation.timeEnd();
            case CLEAR -> null;
        };

        String raw = mutation.raw() == null || mutation.raw().isBlank() ? base.raw() : mutation.raw();
        if (dateStart == null && dateEnd == null && timeStart == null && timeEnd == null) {
            return TimeConstraint.empty();
        }
        return new TimeConstraint(raw, dateStart, dateEnd, timeStart, timeEnd, LocalDateTime.now(ZONE));
    }
}
