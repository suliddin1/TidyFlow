package com.tidyflow.ui;

import com.tidyflow.data.config.ThemeMode;
import com.tidyflow.util.WindowsThemeDetector;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the active application theme (SYSTEM, LIGHT, DARK), applies base.css + palette CSS
 * to all open scenes and dialogs immediately, and attaches window icons.
 */
public final class ThemeManager {

    private static final String BASE_CSS = "/com/tidyflow/ui/base.css";
    private static final String LIGHT_CSS = "/com/tidyflow/ui/light.css";
    private static final String DARK_CSS = "/com/tidyflow/ui/dark.css";
    private static final List<String> ICON_PATHS = List.of(
            "/icons/icon-16.png",
            "/icons/icon-32.png",
            "/icons/icon-48.png",
            "/icons/icon-64.png",
            "/icons/icon-128.png",
            "/icons/icon-256.png"
    );

    private static volatile ThemeMode currentPreference = ThemeMode.SYSTEM;
    private static volatile ThemeMode resolvedPalette = ThemeMode.LIGHT;

    private ThemeManager() {
    }

    public static ThemeMode currentPreference() {
        return currentPreference;
    }

    public static ThemeMode resolvedPalette() {
        return resolvedPalette;
    }

    /**
     * Resolves the theme preference (querying Windows registry if SYSTEM) and immediately
     * updates all open JavaFX windows and dialogs.
     */
    public static void applyTheme(ThemeMode preference) {
        ThemeMode mode = preference != null ? preference : ThemeMode.SYSTEM;
        currentPreference = mode;
        resolvedPalette = (mode == ThemeMode.SYSTEM)
                ? WindowsThemeDetector.detectSystemTheme()
                : mode;

        for (Window window : Window.getWindows()) {
            if (window.getScene() != null) {
                applyToScene(window.getScene());
            }
        }
    }

    public static void applyToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        List<String> urls = resolveStylesheetUrls(resolvedPalette);
        scene.getStylesheets().setAll(urls);
    }

    public static void styleDialog(Dialog<?> dialog) {
        if (dialog == null) {
            return;
        }
        styleDialogPane(dialog.getDialogPane());
        dialog.setOnShown(e -> {
            Window window = dialog.getDialogPane().getScene() != null
                    ? dialog.getDialogPane().getScene().getWindow()
                    : null;
            if (window instanceof Stage stage) {
                applyWindowIcons(stage);
            }
        });
    }

    public static void styleDialogPane(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }
        List<String> urls = resolveStylesheetUrls(resolvedPalette);
        dialogPane.getStylesheets().setAll(urls);
    }

    public static void applyWindowIcons(Stage stage) {
        if (stage == null) {
            return;
        }
        List<Image> icons = new ArrayList<>();
        for (String path : ICON_PATHS) {
            try (InputStream in = ThemeManager.class.getResourceAsStream(path)) {
                if (in != null) {
                    icons.add(new Image(in));
                }
            } catch (Exception ignored) {
            }
        }
        if (!icons.isEmpty()) {
            stage.getIcons().setAll(icons);
        }
    }

    private static List<String> resolveStylesheetUrls(ThemeMode palette) {
        List<String> result = new ArrayList<>(2);
        String paletteResource = (palette == ThemeMode.DARK) ? DARK_CSS : LIGHT_CSS;
        addUrlIfPresent(result, paletteResource);
        addUrlIfPresent(result, BASE_CSS);
        return result;
    }

    private static void addUrlIfPresent(List<String> list, String resourcePath) {
        URL url = ThemeManager.class.getResource(resourcePath);
        if (url != null) {
            list.add(url.toExternalForm());
        }
    }
}
