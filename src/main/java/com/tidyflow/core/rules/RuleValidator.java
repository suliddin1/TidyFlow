package com.tidyflow.core.rules;

import com.tidyflow.util.PathSafety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates Rule instances and returns human-readable error messages without throwing exceptions.
 */
public class RuleValidator {

    private static final String ILLEGAL_WINDOWS_CHARS = "<>\"|?*";

    public List<String> validateRule(Rule rule, List<Rule> otherRules) {
        List<String> errors = new ArrayList<>();
        if (rule == null) {
            errors.add("Rule must not be null");
            return List.copyOf(errors);
        }

        // 1. Name not blank and unique (case-insensitive)
        String trimmedName = rule.name() != null ? rule.name().trim() : "";
        if (trimmedName.isEmpty()) {
            errors.add("Rule name must not be blank");
        } else if (otherRules != null) {
            boolean duplicate = otherRules.stream()
                    .filter(other -> other != null && other.name() != null)
                    .anyMatch(other -> other.name().trim().equalsIgnoreCase(trimmedName));
            if (duplicate) {
                errors.add("Rule name '" + trimmedName + "' must be unique (case-insensitive)");
            }
        }

        // 2. Priority >= 1
        if (rule.priority() < 1) {
            errors.add("Priority must be an integer >= 1");
        }

        // 3. At least one condition
        List<Condition> conditions = rule.conditions();
        if (conditions == null || conditions.isEmpty()) {
            errors.add("Rule must have at least one condition");
        } else {
            for (Condition condition : conditions) {
                validateCondition(condition, errors);
            }
        }

        // 4. Action target folder validation
        Action action = rule.action();
        if (action == null) {
            errors.add("Rule must specify an action");
        } else if (action instanceof MoveAction moveAction) {
            validateMoveAction(moveAction, errors);
        }

        return List.copyOf(errors);
    }

    public List<String> validateAll(List<Rule> rules) {
        List<String> errors = new ArrayList<>();
        if (rules == null) {
            errors.add("Rules list must not be null");
            return List.copyOf(errors);
        }

        Set<String> seenNames = new HashSet<>();
        for (Rule rule : rules) {
            List<String> singleErrors = validateRule(rule, List.of());
            errors.addAll(singleErrors);

            if (rule != null && rule.name() != null && !rule.name().trim().isEmpty()) {
                String lower = rule.name().trim().toLowerCase(Locale.ROOT);
                if (!seenNames.add(lower)) {
                    errors.add("Duplicate rule name '" + rule.name().trim() + "' (names must be unique case-insensitively)");
                }
            }
        }
        return List.copyOf(errors);
    }

    private void validateCondition(Condition condition, List<String> errors) {
        if (condition == null) {
            errors.add("Condition must not be null");
            return;
        }
        if (condition instanceof ExtensionCondition extCond) {
            Set<String> exts = extCond.allowedExtensions();
            if (exts.isEmpty()) {
                errors.add("Extension list must not be empty");
            } else {
                for (String ext : exts) {
                    if (ext.isEmpty() || ext.contains(".") || containsWhitespace(ext)) {
                        errors.add("Extension '" + ext + "' must not contain dots or spaces");
                    }
                }
            }
        } else if (condition instanceof RegexFilenameCondition regexCond) {
            if (!regexCond.isValidRegex()) {
                errors.add("Invalid filename regex: " + regexCond.syntaxError());
            }
        } else if (condition instanceof MinSizeCondition sizeCond) {
            if (sizeCond.minBytes() <= 0) {
                errors.add("Minimum size must be a positive number");
            }
        } else if (condition instanceof OlderThanDaysCondition daysCond) {
            if (daysCond.days() <= 0) {
                errors.add("Days threshold must be a positive number");
            }
        }
    }

    private void validateMoveAction(MoveAction moveAction, List<String> errors) {
        String raw = moveAction.rawTargetFolder();
        if (raw == null || raw.isBlank()) {
            errors.add("Target folder must not be blank");
            return;
        }
        String trimmed = raw.trim();
        if (hasIllegalWindowsPathCharacters(trimmed) || moveAction.targetFolder() == null) {
            errors.add("Target folder contains illegal Windows path characters: " + trimmed);
            return;
        }
        if (PathSafety.isProtectedPath(moveAction.targetFolder())) {
            errors.add("Target folder must not be inside a protected system directory: " + trimmed);
        }
    }

    public static boolean hasIllegalWindowsPathCharacters(String pathText) {
        for (int i = 0; i < pathText.length(); i++) {
            char c = pathText.charAt(i);
            if (c < 32 || ILLEGAL_WINDOWS_CHARS.indexOf(c) >= 0) {
                return true;
            }
            if (c == ':') {
                boolean validDriveColon = (i == 1) && Character.isLetter(pathText.charAt(0));
                if (!validDriveColon) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
