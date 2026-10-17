package com.tidyflow.data.config;

import com.tidyflow.core.rules.Rule;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of loading rules from rules.json, including any recovery notice if the file was corrupt or had an unknown version.
 */
public record RuleLoadResult(
        List<Rule> rules,
        boolean recoveredFromBackup,
        Path backupPath,
        String warningMessage
) {
    public RuleLoadResult {
        Objects.requireNonNull(rules, "rules must not be null");
        rules = List.copyOf(rules);
    }

    public static RuleLoadResult normal(List<Rule> rules) {
        return new RuleLoadResult(rules, false, null, null);
    }

    public static RuleLoadResult recovered(List<Rule> rules, Path backupPath, String warningMessage) {
        return new RuleLoadResult(rules, true, backupPath, warningMessage);
    }
}
