package com.tidyflow.ui;

import javafx.application.Platform;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Thin AWT SystemTray integration that marshals user actions onto the JavaFX thread
 * and degrades gracefully when SystemTray is unsupported.
 */
public class SystemTrayManager implements AutoCloseable {

    private final Runnable onOpenWindow;
    private final Runnable onTogglePause;
    private final Runnable onOrganizeNow;
    private final Consumer<Path> onOpenOrganizeForFolder;
    private final Runnable onOpenHistory;
    private final Runnable onQuit;
    private final BooleanSupplier showNotificationsSupplier;

    private volatile boolean supportedAndInstalled = false;
    private volatile boolean shownBackgroundNoticeOnce = false;
    private volatile Runnable pendingNotificationClickAction;
    private TrayIcon trayIcon;
    private MenuItem pauseResumeItem;

    public SystemTrayManager(
            Runnable onOpenWindow,
            Runnable onTogglePause,
            Runnable onOrganizeNow,
            Consumer<Path> onOpenOrganizeForFolder,
            Runnable onOpenHistory,
            Runnable onQuit,
            BooleanSupplier showNotificationsSupplier
    ) {
        this.onOpenWindow = Objects.requireNonNull(onOpenWindow);
        this.onTogglePause = Objects.requireNonNull(onTogglePause);
        this.onOrganizeNow = Objects.requireNonNull(onOrganizeNow);
        this.onOpenOrganizeForFolder = Objects.requireNonNull(onOpenOrganizeForFolder);
        this.onOpenHistory = Objects.requireNonNull(onOpenHistory);
        this.onQuit = Objects.requireNonNull(onQuit);
        this.showNotificationsSupplier = Objects.requireNonNull(showNotificationsSupplier);
    }

    public void initialize() {
        if (!SystemTray.isSupported()) {
            System.out.println("[TidyFlow] SystemTray is not supported on this platform; window close will exit the app.");
            supportedAndInstalled = false;
            return;
        }

        Platform.setImplicitExit(false);
        SwingUtilities.invokeLater(() -> {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                Image image = loadOrCreateTrayImage();

                PopupMenu menu = new PopupMenu();
                MenuItem openItem = new MenuItem("Open TidyFlow");
                openItem.addActionListener(e -> Platform.runLater(onOpenWindow));

                pauseResumeItem = new MenuItem("Pause watching");
                pauseResumeItem.addActionListener(e -> Platform.runLater(onTogglePause));

                MenuItem organizeNowItem = new MenuItem("Organize now");
                organizeNowItem.addActionListener(e -> Platform.runLater(onOrganizeNow));

                MenuItem quitItem = new MenuItem("Quit");
                quitItem.addActionListener(e -> Platform.runLater(onQuit));

                menu.add(openItem);
                menu.add(pauseResumeItem);
                menu.add(organizeNowItem);
                menu.addSeparator();
                menu.add(quitItem);

                trayIcon = new TrayIcon(image, "TidyFlow", menu);
                trayIcon.setImageAutoSize(true);
                // Double-click or action event on tray icon / balloon notification
                trayIcon.addActionListener(e -> Platform.runLater(() -> {
                    Runnable clickAction = pendingNotificationClickAction;
                    pendingNotificationClickAction = null;
                    if (clickAction != null) {
                        clickAction.run();
                    } else {
                        onOpenWindow.run();
                    }
                }));

                tray.add(trayIcon);
                supportedAndInstalled = true;
            } catch (AWTException e) {
                System.out.println("[TidyFlow] Failed to add SystemTray icon: " + e.getMessage());
                supportedAndInstalled = false;
            }
        });
    }

    public boolean isSupportedAndInstalled() {
        return supportedAndInstalled || SystemTray.isSupported();
    }

    public void updatePausedState(boolean paused) {
        SwingUtilities.invokeLater(() -> {
            if (pauseResumeItem != null) {
                pauseResumeItem.setLabel(paused ? "Resume watching" : "Pause watching");
            }
        });
    }

    public void notifyMinimizedToTrayOnce() {
        if (shownBackgroundNoticeOnce) {
            return;
        }
        shownBackgroundNoticeOnce = true;
        showNotification(
                "TidyFlow",
                "TidyFlow is still running in the background",
                TrayIcon.MessageType.INFO,
                onOpenWindow
        );
    }

    public void notifyBatchOrganized(int movedCount) {
        String text = movedCount == 1 ? "Organized 1 file" : "Organized " + movedCount + " files";
        showNotification("TidyFlow", text, TrayIcon.MessageType.INFO, onOpenHistory);
    }

    public void notifyFilesReadyForReview(Path folder, int count, boolean triggeredByGuard, int threshold) {
        String message = triggeredByGuard
                ? "Mass-move guard (> " + threshold + " files): " + count + " files in " + folder.getFileName() + " waiting for review"
                : count + " files ready in " + folder.getFileName() + " (click to review)";
        showNotification(
                "TidyFlow — Review Files",
                message,
                triggeredByGuard ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO,
                () -> onOpenOrganizeForFolder.accept(folder)
        );
    }

    private void showNotification(String title, String message, TrayIcon.MessageType type, Runnable onClick) {
        if (!showNotificationsSupplier.getAsBoolean()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null) {
                pendingNotificationClickAction = onClick;
                trayIcon.displayMessage(title, message, type);
            }
        });
    }

    private static Image loadOrCreateTrayImage() {
        for (String path : new String[]{"/icons/icon-32.png", "/icons/icon-16.png", "/com/tidyflow/ui/tray-icon.png"}) {
            try (InputStream in = SystemTrayManager.class.getResourceAsStream(path)) {
                if (in != null) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        return img;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(37, 99, 235));
            g.fillRoundRect(2, 2, 28, 28, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.drawString("T", 10, 23);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(() -> {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
        });
    }
}
