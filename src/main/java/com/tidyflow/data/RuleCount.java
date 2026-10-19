package com.tidyflow.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated count of organized files for a single rule name.
 */
public record RuleCount(String ruleName, long count) {

    public static final int TOP_RULES_LIMIT = 8;
    public static final String OTHER_RULE_LABEL = "Other";

    public RuleCount {
        Objects.requireNonNull(ruleName, "ruleName must not be null");
    }

    /**
     * Collapses a descending-sorted list of RuleCount entries into at most the top 8 rules
     * plus a single "Other" bucket summing the remaining rules.
     */
    public static List<RuleCount> topEightPlusOther(List<RuleCount> sortedDescending) {
        Objects.requireNonNull(sortedDescending, "sortedDescending must not be null");
        if (sortedDescending.size() <= TOP_RULES_LIMIT) {
            return List.copyOf(sortedDescending);
        }

        List<RuleCount> result = new ArrayList<>(TOP_RULES_LIMIT + 1);
        for (int i = 0; i < TOP_RULES_LIMIT; i++) {
            result.add(sortedDescending.get(i));
        }

        long otherSum = 0L;
        for (int i = TOP_RULES_LIMIT; i < sortedDescending.size(); i++) {
            otherSum += sortedDescending.get(i).count();
        }
        if (otherSum > 0L) {
            result.add(new RuleCount(OTHER_RULE_LABEL, otherSum));
        }
        return List.copyOf(result);
    }
}
