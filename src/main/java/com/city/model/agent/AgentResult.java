package com.city.model.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Worker 的结构化返回；自然语言摘要不能替代实体与证据字段。 */
public record AgentResult(
        Status status,
        String summary,
        Set<Long> verifiedActivityIds,
        Set<Long> verifiedSessionIds,
        List<EvidenceRef> evidenceRefs,
        List<String> warnings,
        Map<String, Number> metrics
) {
    public AgentResult {
        status = status == null ? Status.ERROR : status;
        summary = summary == null ? "" : summary;
        verifiedActivityIds = verifiedActivityIds == null ? Set.of() : Set.copyOf(verifiedActivityIds);
        verifiedSessionIds = verifiedSessionIds == null ? Set.of() : Set.copyOf(verifiedSessionIds);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        Set<Long> evidencedActivities = evidenceRefs.stream()
                .filter(ref -> ref.type() == EvidenceType.ACTIVITY)
                .map(EvidenceRef::id)
                .map(AgentResult::parseLong)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Long> evidencedSessions = evidenceRefs.stream()
                .filter(ref -> ref.type() == EvidenceType.ACTIVITY_SESSION)
                .map(EvidenceRef::id)
                .map(AgentResult::parseLong)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!evidencedActivities.containsAll(verifiedActivityIds)
                || !evidencedSessions.containsAll(verifiedSessionIds)) {
            throw new IllegalArgumentException("已验证实体缺少对应 EvidenceRef");
        }
    }

    public enum Status {
        COMPLETED,
        PARTIAL,
        REJECTED,
        ERROR
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
