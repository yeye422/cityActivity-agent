package com.city.model;

import java.util.List;

/** 严格检索无结果时，用户可主动选择的放宽检索方案。 */
public record RelaxationOption(int level, String label, List<String> relaxedSlots, int candidateCount) {
}
