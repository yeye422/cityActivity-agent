package com.city.service.agent;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.EvidenceRef;
import com.city.model.agent.EvidenceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** 从已验证业务实体生成不可变证据指纹。 */
public final class EvidenceRefFactory {
    public EvidenceRef activity(ActivityItem item) {
        if (item == null || item.id() == null) throw new IllegalArgumentException("活动证据缺少活动 ID");
        String canonical = String.join("|",
                String.valueOf(item.id()),
                String.valueOf(item.sourceType()),
                String.valueOf(item.ownerUserId()),
                String.valueOf(item.name()),
                String.valueOf(item.slots()),
                String.valueOf(item.validFrom()),
                String.valueOf(item.validTo()),
                String.valueOf(item.validStartTime()),
                String.valueOf(item.validEndTime()),
                String.valueOf(item.durationMinutes()));
        return new EvidenceRef(EvidenceType.ACTIVITY, item.id().toString(), sha256(canonical), Instant.now());
    }

    public EvidenceRef session(ActivitySessionResponse session) {
        if (session == null || session.sessionId() == null) {
            throw new IllegalArgumentException("场次证据缺少场次 ID");
        }
        String canonical = String.join("|",
                String.valueOf(session.sessionId()), String.valueOf(session.activityId()),
                String.valueOf(session.venueId()), String.valueOf(session.startAt()),
                String.valueOf(session.endAt()), String.valueOf(session.price()),
                String.valueOf(session.remainingSeats()), String.valueOf(session.status()));
        return new EvidenceRef(
                EvidenceType.ACTIVITY_SESSION, session.sessionId().toString(), sha256(canonical), Instant.now());
    }

    public EvidenceRef venue(ActivitySessionResponse session) {
        if (session == null || session.venueId() == null) {
            throw new IllegalArgumentException("场地证据缺少场地 ID");
        }
        String canonical = String.join("|",
                String.valueOf(session.venueId()), String.valueOf(session.venueName()),
                String.valueOf(session.venueType()), String.valueOf(session.city()),
                String.valueOf(session.district()), String.valueOf(session.address()));
        return new EvidenceRef(EvidenceType.VENUE, session.venueId().toString(), sha256(canonical), Instant.now());
    }

    public EvidenceRef weather(WeatherRecommendationContext weather,
                               SlotBundle slots,
                               TimeConstraint timeConstraint) {
        if (weather == null || weather.status() == null) {
            throw new IllegalArgumentException("天气证据缺少状态");
        }
        String city = slots == null || slots.city().isEmpty() ? "UNKNOWN" : slots.city().getFirst();
        String date = timeConstraint == null ? "UNKNOWN" : String.valueOf(timeConstraint.dateStart());
        String id = city + '@' + date;
        String canonical = id + '|' + weather.status() + '|' + weather.summary();
        return new EvidenceRef(EvidenceType.WEATHER, id, sha256(canonical), Instant.now());
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
