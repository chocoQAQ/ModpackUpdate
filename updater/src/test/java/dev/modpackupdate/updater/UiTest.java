package dev.modpackupdate.updater;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class UiTest {
    @Test void localPackagePickerUsesTheChosenFile() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var ui = new UpdateUi();
        var executor = Executors.newSingleThreadExecutor();
        Path first = Path.of("MyPack-1.2.0.mrpack");
        Path second = Path.of("MyPack-1.3.0-offline.mrpack");
        try {
            var result = executor.submit(() -> ui.chooseLocal(List.of(first, second)));
            Window dialog = awaitDialog("选择本地更新包");
            SwingUtilities.invokeAndWait(() -> {
                find(dialog, JComboBox.class).get(0).setSelectedIndex(1);
                capture(dialog, "local-package-picker.png");
                click(dialog, "检查所选更新包");
            });
            assertEquals(second, result.get(5, TimeUnit.SECONDS));
        } finally {
            ui.close();
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
            executor.shutdownNow();
        }
    }

    @Test void fallbackCheckCanBeSkippedWithoutAnInstallationDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var ui = new UpdateUi();
        try {
            ui.checking(() -> cancelled.set(true));
            SwingUtilities.invokeAndWait(() -> {
                Window window = java.util.Arrays.stream(Window.getWindows()).filter(Window::isShowing).findFirst().orElseThrow();
                capture(window, "fallback-check.png");
                click(window, "跳过检查");
            });
            assertTrue(cancelled.get());
            ui.closeChecking();
            SwingUtilities.invokeAndWait(() -> assertTrue(java.util.Arrays.stream(Window.getWindows()).noneMatch(Window::isShowing)));
        } finally { ui.closeChecking(); }
    }
    @Test void confirmationProgressAndCompletionWork() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        UpdateUi ui = new UpdateUi();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Models.Installed installed = new Models.Installed();
            installed.version = "1.4.0";
            Models.Release release = new Models.Release(1, "adventure", 2, "1.5.0",
                    "更新内容\n\n• 更新部分模组\n• 优化启动速度\n• 修复配置冲突", null, null, null);
            var confirmation = executor.submit(() -> ui.confirm(installed, release, true));
            Window confirm = awaitDialog("发现更新");
            SwingUtilities.invokeAndWait(() -> { capture(confirm, "confirmation.png"); click(confirm, "取消"); });
            assertFalse(confirmation.get(5, TimeUnit.SECONDS));
            ui.progress(UpdateUi.Stage.DOWNLOAD, "正在下载 · mods/example-adventure-1.5.0.jar", 3, 8);
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = java.util.Arrays.stream(Window.getWindows()).filter(Window::isShowing)
                        .filter(JFrame.class::isInstance).map(JFrame.class::cast).findFirst().orElseThrow();
                JProgressBar bar = find(frame, JProgressBar.class).get(0);
                assertFalse(bar.isIndeterminate());
                assertEquals(3, bar.getValue());
                assertEquals(8, bar.getMaximum());
                capture(frame, "progress.png");
            });
            ui.progress(UpdateUi.Stage.INSTALL, "安装文件并保存版本记录…", 0, 0);
            var completion = executor.submit(() -> { ui.message("已更新至 1.5.0。\n保留本地差异 2 项。\n请从启动器重新启动游戏。", false); return null; });
            Window done = awaitDialog("更新完成");
            SwingUtilities.invokeAndWait(() -> { capture(done, "completion.png"); click(done, "关闭"); });
            completion.get(5, TimeUnit.SECONDS);
        } finally {
            ui.close();
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
            executor.shutdownNow();
        }
    }

    private static Window awaitDialog(String title) throws Exception {
        Window[] result = {null};
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (result[0] == null && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isShowing() && dialog.getTitle().endsWith(title)) result[0] = dialog;
                }
            });
            if (result[0] == null) Thread.sleep(50);
        }
        assertNotNull(result[0]);
        return result[0];
    }

    private static void capture(Window window, String name) {
        try {
            Path output = Path.of(System.getProperty("qa.directory"), name);
            Files.createDirectories(output.getParent());
            BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            window.paintAll(graphics);
            graphics.dispose();
            ImageIO.write(image, "png", output.toFile());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test void conflictCheckboxesAndBulkSelectionWork() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        UpdateUi ui = new UpdateUi();
        Models.Target target = new Models.Target("config/example.json", "a".repeat(128), 1, List.of(), true, false);
        Models.Change conflict = new Models.Change(target.path(), target, "b".repeat(128), "本地文件已修改或移除");
        Models.Target optional = new Models.Target("mods/optional.jar", "c".repeat(128), 1, List.of(), false, true);
        Models.Change optionalChange = new Models.Change(optional.path(), optional, null, "可选模组");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var result = executor.submit(() -> ui.choose(List.of(conflict, optionalChange), new Models.Config()));
        try {
            Window[] dialog = {null};
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (dialog[0] == null && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window.isShowing() && !find(window, JTable.class).isEmpty()) dialog[0] = window;
                    }
                });
                if (dialog[0] == null) Thread.sleep(50);
            }
            assertNotNull(dialog[0]);
            SwingUtilities.invokeAndWait(() -> {
                JTable table = find(dialog[0], JTable.class).get(0);
                assertEquals(true, table.getValueAt(0, 0));
                assertEquals(false, table.getValueAt(1, 0));
                JCheckBox delete = find(dialog[0], JCheckBox.class).get(0);
                assertFalse(delete.isSelected());
                click(dialog[0], "全选");
                assertEquals(true, table.getValueAt(0, 0));
                click(dialog[0], "全不选");
                assertEquals(false, table.getValueAt(0, 0));
                table.setValueAt(true, 0, 0);
                try {
                    Path output = Path.of(System.getProperty("qa.directory"), "conflicts.png");
                    Files.createDirectories(output.getParent());
                    BufferedImage image = new BufferedImage(dialog[0].getWidth(), dialog[0].getHeight(), BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    dialog[0].paintAll(graphics);
                    graphics.dispose();
                    ImageIO.write(image, "png", output.toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
                click(dialog[0], "继续安装");
            });
            UpdateUi.Selection selection = result.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(conflict), selection.changes());
            assertFalse(selection.deleteReplacedMods());
        } finally {
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
            executor.shutdownNow();
        }
    }
    private static void click(Container parent, String label) {
        find(parent, JButton.class).stream().filter(button -> label.equals(button.getText())).findFirst().orElseThrow().doClick();
    }
    private static <T> List<T> find(Container parent, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) matches.add(type.cast(child));
            if (child instanceof Container container) matches.addAll(find(container, type));
        }
        return matches;
    }
}
