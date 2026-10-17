package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches files whose filename matches a regular expression.
 */
public final class RegexFilenameCondition implements Condition {

    private final String regex;
    private final Pattern pattern;
    private final String syntaxError;

    public RegexFilenameCondition(String regex) {
        this.regex = Objects.requireNonNull(regex, "regex must not be null");
        Pattern compiled = null;
        String error = null;
        if (regex.isBlank()) {
            error = "Regular expression must not be blank";
        } else {
            try {
                compiled = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                error = e.getDescription();
            }
        }
        this.pattern = compiled;
        this.syntaxError = error;
    }

    public RegexFilenameCondition(Pattern pattern) {
        this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
        this.regex = pattern.pattern();
        this.syntaxError = null;
    }

    public String regex() {
        return regex;
    }

    public Pattern pattern() {
        return pattern;
    }

    public boolean isValidRegex() {
        return pattern != null && syntaxError == null;
    }

    public String syntaxError() {
        return syntaxError;
    }

    @Override
    public boolean matches(FileItem file) {
        if (file == null || pattern == null) {
            return false;
        }
        return pattern.matcher(file.name()).matches();
    }
}
