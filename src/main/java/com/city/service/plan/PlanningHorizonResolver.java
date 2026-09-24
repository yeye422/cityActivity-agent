package com.city.service.plan;

import com.city.model.TimeConstraint;
import com.city.model.context.PlanningHorizon;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 将已验证日期/时间约束转换成 Planning 的绝对时间范围，不再切固定 2 小时窗口。 */
@Service
public class PlanningHorizonResolver {

    private static final LocalTime DEFAULT_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_END = LocalTime.of(23, 0);

    public PlanningHorizon resolve(TimeConstraint constraint) {
        if (constraint == null || !constraint.hasDate()) {
            return PlanningHorizon.empty();
        }

        LocalDate dateStart = constraint.dateStart();
        LocalDate dateEnd = constraint.dateEnd();
        if (dateStart == null || dateEnd == null || dateEnd.isBefore(dateStart)) {
            throw new IllegalArgumentException("规划日期范围无效");
        }

        LocalTime startTime = constraint.hasTime() ? constraint.startTime() : DEFAULT_START;
        LocalTime endTime = constraint.hasTime() ? constraint.endTime() : DEFAULT_END;
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("规划时间范围无效");
        }

        List<PlanningHorizon.Range> ranges = new ArrayList<>();
        for (LocalDate date = dateStart; !date.isAfter(dateEnd); date = date.plusDays(1)) {
            ranges.add(new PlanningHorizon.Range(
                    date.atTime(startTime),
                    date.atTime(endTime)
            ));
        }
        return new PlanningHorizon(ranges);
    }
}
