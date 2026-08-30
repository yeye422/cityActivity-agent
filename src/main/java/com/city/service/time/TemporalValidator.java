package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import org.springframework.stereotype.Service;

/**
 * 校验 LLM 输出的时间结构，避免格式合法但区间自相矛盾的结果进入会话状态。
 */
@Service
public class TemporalValidator {

    public boolean isValid(TemporalMutation mutation) {
        if (mutation == null || mutation.dateMode() == null || mutation.timeMode() == null) {
            return false;
        }

        if (mutation.dateMode() == TemporalMode.SET) {
            if (mutation.dateStart() == null || mutation.dateEnd() == null
                    || mutation.dateEnd().isBefore(mutation.dateStart())) {
                return false;
            }
        } else if (mutation.dateStart() != null || mutation.dateEnd() != null) {
            return false;
        }

        if (mutation.timeMode() == TemporalMode.SET) {
            if (mutation.timeStart() == null || mutation.timeEnd() == null
                    || !mutation.timeStart().isBefore(mutation.timeEnd())) {
                return false;
            }
        } else if (mutation.timeStart() != null || mutation.timeEnd() != null) {
            return false;
        }

        return mutation.confidence() >= 0.0 && mutation.confidence() <= 1.0;
    }
}
