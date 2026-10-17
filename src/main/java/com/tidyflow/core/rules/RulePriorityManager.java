package com.tidyflow.core.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Handles reordering rules (move up / move down) and renumbering priorities cleanly (1..N)
 * so duplicate priorities never occur.
 */
public final class RulePriorityManager {

    private RulePriorityManager() {
    }

    public static List<Rule> normalizePriorities(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        List<Rule> sorted = rules.stream()
                .sorted(Comparator.comparingInt(Rule::priority))
                .toList();
        return renumberSequential(sorted);
    }

    public static List<Rule> moveUp(List<Rule> rules, int index) {
        List<Rule> ordered = new ArrayList<>(normalizePriorities(rules));
        if (index <= 0 || index >= ordered.size()) {
            return List.copyOf(ordered);
        }
        Rule temp = ordered.get(index - 1);
        ordered.set(index - 1, ordered.get(index));
        ordered.set(index, temp);
        return renumberSequential(ordered);
    }

    public static List<Rule> moveDown(List<Rule> rules, int index) {
        List<Rule> ordered = new ArrayList<>(normalizePriorities(rules));
        if (index < 0 || index >= ordered.size() - 1) {
            return List.copyOf(ordered);
        }
        Rule temp = ordered.get(index + 1);
        ordered.set(index + 1, ordered.get(index));
        ordered.set(index, temp);
        return renumberSequential(ordered);
    }

    public static List<Rule> renumberSequential(List<Rule> orderedRules) {
        List<Rule> renumbered = new ArrayList<>(orderedRules.size());
        for (int i = 0; i < orderedRules.size(); i++) {
            Rule r = orderedRules.get(i);
            renumbered.add(new Rule(r.name(), r.enabled(), i + 1, r.conditions(), r.action()));
        }
        return List.copyOf(renumbered);
    }

    public static String summarizeConditions(Rule rule) {
        if (rule == null || rule.conditions().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Condition c : rule.conditions()) {
            if (c instanceof ExtensionCondition ext) {
                parts.add("ext in [" + String.join(", ", ext.extensionList()) + "]");
            } else if (c instanceof RegexFilenameCondition rx) {
                parts.add("name ~ /" + rx.regex() + "/");
            } else if (c instanceof MinSizeCondition sz) {
                parts.add("size > " + sz.minBytes() + " B");
            } else if (c instanceof OlderThanDaysCondition od) {
                parts.add("older > " + od.days() + "d");
            }
        }
        return String.join(" AND ", parts);
    }

    public static String summarizeAction(Rule rule) {
        if (rule == null || rule.action() == null) {
            return "";
        }
        if (rule.action() instanceof MoveAction move) {
            return move.rawTargetFolder();
        }
        return rule.action().toString();
    }
}
