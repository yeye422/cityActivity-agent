package com.city.model.agent;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 PlanningAgent Run 的显式计划状态。
 *
 * <p>Notebook 不判断业务合法性，合法性仍由 PlanningSolver/validate_plan 决定；
 * 它只记录 Agent 已完成的 Discovery、Travel、Validate、Repair 状态，使 ReAct 规划循环可观测、可测试。</p>
 */
public final class PlanNotebook {

    public enum Status {
        DISCOVERY_PENDING,
        READY_TO_PROPOSE,
        VALIDATING,
        REPAIR_REQUIRED,
        VALIDATED
    }

    private final List<String> windows;
    private final Set<String> discoveredPeriods = new LinkedHashSet<>();
    private Status status = Status.DISCOVERY_PENDING;
    private PlanProposal latestProposal;
    private List<PlanValidationResult.Violation> latestViolations = List.of();
    private int travelLookups;
    private int validationAttempts;
    private int repairAttempts;

    public PlanNotebook(List<String> windows) {
        this.windows = windows == null ? List.of() : List.copyOf(windows);
    }

    public synchronized void recordDiscovery(Collection<String> periods) {
        discoveredPeriods.clear();
        if (periods != null) {
            periods.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(discoveredPeriods::add);
        }
        status = Status.READY_TO_PROPOSE;
    }

    public synchronized void recordTravelLookup() {
        travelLookups++;
    }

    /**
     * 开始一次 validate_plan。返回 true 表示这是上一轮 invalid 后提交的修复方案。
     */
    public synchronized boolean beginValidation(PlanProposal proposal) {
        boolean repairing = status == Status.REPAIR_REQUIRED;
        if (repairing) repairAttempts++;
        latestProposal = proposal;
        latestViolations = List.of();
        validationAttempts++;
        status = Status.VALIDATING;
        return repairing;
    }

    public synchronized void completeValidation(PlanValidationResult result) {
        if (result != null && result.valid()) {
            latestViolations = List.of();
            status = Status.VALIDATED;
            return;
        }
        latestViolations = result == null || result.violations() == null
                ? List.of()
                : List.copyOf(result.violations());
        status = Status.REPAIR_REQUIRED;
    }

    public synchronized boolean validated() {
        return status == Status.VALIDATED;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                windows,
                List.copyOf(discoveredPeriods),
                status,
                latestProposal,
                latestViolations,
                travelLookups,
                validationAttempts,
                repairAttempts
        );
    }

    public record Snapshot(
            List<String> windows,
            List<String> discoveredPeriods,
            Status status,
            PlanProposal latestProposal,
            List<PlanValidationResult.Violation> latestViolations,
            int travelLookups,
            int validationAttempts,
            int repairAttempts
    ) {}
}
