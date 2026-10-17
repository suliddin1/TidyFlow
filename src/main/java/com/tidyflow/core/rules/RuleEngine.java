package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates scanned files against rules ordered by priority (ascending: lower number = higher priority)
 * and produces dry-run PlannedOperation objects without touching the file system.
 */
public class RuleEngine {

    private final List<Rule> sortedRules;

    public RuleEngine(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        this.sortedRules = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
    }

    public List<PlannedOperation> evaluate(List<FileItem> files) {
        Objects.requireNonNull(files, "files must not be null");
        List<PlannedOperation> plans = new ArrayList<>();

        for (FileItem file : files) {
            for (Rule rule : sortedRules) {
                if (rule.matches(file)) {
                    Path destination = rule.action().resolveDestination(file);
                    plans.add(new PlannedOperation(file.path(), destination, rule.name()));
                    break;
                }
            }
        }
        return List.copyOf(plans);
    }
}
