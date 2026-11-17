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
    @Test void conflictCheckboxesAndBulkSelectionWork() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        UpdateUi ui = new UpdateUi();
        Models.Target target = new Models.Target("config/example.json", "a".repeat(128), 1, List.of(), true, false);
        Models.Change conflict = new Models.Change(target.path(), target, "b".repeat(128), "本地文件已修改或移除");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var result = executor.submit(() -> ui.choose(List.of(conflict), new Models.Config()));
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
                assertEquals(false, table.getValueAt(0, 0));
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
                click(dialog[0], "开始安装");
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
