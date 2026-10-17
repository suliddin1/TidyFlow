package com.tidyflow.core.rules;

import java.util.List;

/**
 * Provides the built-in default rules for common file categories.
 */
public final class DefaultRules {

    private DefaultRules() {
    }

    public static List<Rule> create() {
        return List.of(
                new Rule(
                        "Images",
                        true,
                        10,
                        List.of(new ExtensionCondition(List.of("jpg", "png", "gif", "webp"))),
                        new MoveAction("Images")
                ),
                new Rule(
                        "Documents",
                        true,
                        20,
                        List.of(new ExtensionCondition(List.of("pdf", "docx", "xlsx", "pptx", "txt"))),
                        new MoveAction("Documents")
                ),
                new Rule(
                        "Installers",
                        true,
                        30,
                        List.of(new ExtensionCondition(List.of("exe", "msi"))),
                        new MoveAction("Installers")
                ),
                new Rule(
                        "Archives",
                        true,
                        40,
                        List.of(new ExtensionCondition(List.of("zip", "rar", "7z"))),
                        new MoveAction("Archives")
                ),
                new Rule(
                        "Videos",
                        true,
                        50,
                        List.of(new ExtensionCondition(List.of("mp4", "mkv", "avi"))),
                        new MoveAction("Videos")
                )
        );
    }
}
