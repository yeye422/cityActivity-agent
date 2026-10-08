package com.city.model.agent;

import com.city.model.context.PlanningHorizon;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 PlanningAgent Run 的显式计划状态。
 *
 * <p>Notebook 不判断业务合法性；它只记录候选发现范围、路线查询、校验和修复状态。</p>
 */
public final class PlanNotebook {

    public enum Status {
        DISCOVERY_PENDING,
        READY_TO_PROPOSE,
        VALIDATING,
        REPAIR_REQUIRED,
        VALIDATED
    }

    private final PlanningHorizon horizon;
    private final Set<PlanningHorizon.Range> discoveredRanges = new LinkedHashSet<>();
    private Status status = Status.DISCOVERY_PENDING;
    private PlanProposal latestProposal;
    private List<PlanValidationResult.Violation> latestViolations = List.of();
    private int travelLookups;
    private int validationAttempts;
    private int repairAttempts;

    public PlanNotebook(PlanningHorizon horizon) {
        this.horizon = horizon == null ? PlanningHorizon.empty() : horizon;
    }

    public synchronized void recordDiscovery(Collection<PlanningHorizon.Range> ranges) {
        if (ranges != null) {
            ranges.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(discoveredRanges::add);
        }
        status = Status.READY_TO_PROPOSE;
    }

    public synchronized void recordTravelLookup() {
        travelLookups++;
    }

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
                horizon,
                List.copyOf(discoveredRanges),
                status,
                latestProposal,
                latestViolations,
                travelLookups,
                validationAttempts,
                repairAttempts
        );
    }

    public record Snapshot(
            PlanningHorizon horizon,
            List<PlanningHorizon.Range> discoveredRanges,
            Status status,
            PlanProposal latestProposal,
            List<PlanValidationResult.Violation> latestViolations,
            int travelLookups,
            int validationAttempts,
            int repairAttempts
    ) {}
}
