package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.util.List;
import java.util.Objects;

/**
 * Represents a user-defined or default file organization rule.
 * A rule matches a file only when enabled and all of its conditions match.
 */
public record Rule(
        String name,
        boolean enabled,
        int priority,
        List<Condition> conditions,
        Action action
) {
    public Rule {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");
        Objects.requireNonNull(action, "action must not be null");
        conditions = List.copyOf(conditions);
    }

    public boolean matches(FileItem file) {
        if (!enabled || file == null || conditions.isEmpty()) {
            return false;
        }
        for (Condition condition : conditions) {
            if (!condition.matches(file)) {
                return false;
            }
        }
        return true;
    }
}
