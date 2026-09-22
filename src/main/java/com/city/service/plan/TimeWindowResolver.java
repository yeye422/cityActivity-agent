package com.city.service.plan;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 规划时间窗口的确定性解析器。
 *
 * <p>只把已验证的 TimeConstraint 投影成规划候选发现窗口，不负责检索、Agent 决策或求解。
 * 该职责从旧 PlanningWorker / ActivityPlanService 中独立出来，供 PlanningWorkflow 直接使用。</p>
 */
@Service
public class TimeWindowResolver {

    private static final List<PlanWindow> PLAN_WINDOWS = List.of(
            new PlanWindow("08:00-10:00", LocalTime.of(8, 0), LocalTime.of(10, 0)),
            new PlanWindow("10:00-12:00", LocalTime.of(10, 0), LocalTime.of(12, 0)),
            new PlanWindow("12:00-14:00", LocalTime.of(12, 0), LocalTime.of(14, 0)),
            new PlanWindow("14:00-16:00", LocalTime.of(14, 0), LocalTime.of(16, 0)),
            new PlanWindow("16:00-18:00", LocalTime.of(16, 0), LocalTime.of(18, 0)),
            new PlanWindow("18:00-20:00", LocalTime.of(18, 0), LocalTime.of(20, 0)),
            new PlanWindow("20:00-23:00", LocalTime.of(20, 0), LocalTime.of(23, 0))
    );

    public List<String> resolve(SlotBundle slots, TimeConstraint timeConstraint) {
        List<PlanWindow> windows;
        if (timeConstraint != null && timeConstraint.hasTime()) {
            windows = windowsCoveredByTimeRange(timeConstraint.startTime(), timeConstraint.endTime());
            if (windows.isEmpty()) windows = PLAN_WINDOWS;
        } else {
            windows = PLAN_WINDOWS;
        }
        return decorateWindowsWithDate(windows, timeConstraint);
    }

    private List<PlanWindow> windowsCoveredByTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) return List.of();
        return PLAN_WINDOWS.stream()
                .filter(window -> overlaps(start, end, window.start(), window.end()))
                .toList();
    }

    private boolean overlaps(LocalTime start, LocalTime end, LocalTime periodStart, LocalTime periodEnd) {
        return start.isBefore(periodEnd) && end.isAfter(periodStart);
    }

    private List<String> decorateWindowsWithDate(List<PlanWindow> windows, TimeConstraint timeConstraint) {
        String prefix = "";
        if (timeConstraint != null && timeConstraint.hasDate() && timeConstraint.dateStart() != null) {
            prefix = switch (timeConstraint.dateStart().getDayOfWeek()) {
                case SATURDAY -> "周六 ";
                case SUNDAY -> "周日 ";
                default -> "";
            };
        } else if (timeConstraint == null || !timeConstraint.hasDate()) {
            prefix = "周六 ";
        }
        String safePrefix = prefix;
        return windows.stream().map(window -> safePrefix + window.label()).toList();
    }

    private record PlanWindow(String label, LocalTime start, LocalTime end) { }
}
