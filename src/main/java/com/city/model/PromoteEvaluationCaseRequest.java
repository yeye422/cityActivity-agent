package com.city.model;

import com.fasterxml.jackson.databind.JsonNode;

public record PromoteEvaluationCaseRequest(String traceId, JsonNode caseDefinition) { }
