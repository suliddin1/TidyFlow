package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Matches files whose extension belongs to a configured set of extensions (case-insensitive).
 * Extensions are normalized to lowercase without a leading dot.
 */
public final class ExtensionCondition implements Condition {

    private final Set<String> allowedExtensions;

    public ExtensionCondition(Collection<String> extensions) {
        Objects.requireNonNull(extensions, "extensions must not be null");
        this.allowedExtensions = extensions.stream()
                .filter(Objects::nonNull)
                .map(ExtensionCondition::normalizeExtension)
                .filter(ext -> !ext.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String normalizeExtension(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith(".")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public Set<String> allowedExtensions() {
        return Set.copyOf(allowedExtensions);
    }

    public List<String> extensionList() {
        return List.copyOf(allowedExtensions);
    }

    @Override
    public boolean matches(FileItem file) {
        if (file == null || file.extension().isEmpty()) {
            return false;
        }
        return allowedExtensions.contains(file.extension().toLowerCase(Locale.ROOT));
    }
}
