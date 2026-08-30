package com.city.model;
import com.city.enums.Intent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class TraceLabelRequest {
    private Intent expectedIntent;
    private SlotBundle expectedSlots;
    /**
     * 离线评测标签中的预期处理动作，例如 ASK 或 READY。
     * 这是 Trace 标签，而非已移除 ClarifyAgent 的领域枚举。
     */
    private String expectedClarifyAction;
    private String labelNote;
}
