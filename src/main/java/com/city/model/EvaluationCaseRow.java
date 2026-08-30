package com.city.model;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class EvaluationCaseRow { private Long id; private Long userId; private String caseId; private String evalSetVersion; private String caseJson; private String sourceTraceId; private Long createdBy; private LocalDateTime createdAt; }
