package dev.modpackupdate.updater;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Standalone Swing UI: no native libraries or additional runtime dependencies. */
final class UpdateUi {
    private static final Color BACKGROUND = new Color(0xF5F5F5);
    private static final Color INK = new Color(0x202020);
    private static final Color MUTED = new Color(0x707070);
    private static final Color ACCENT = new Color(0x303030);
    private static final Color LINE = new Color(0xE2E2E2);
    private static final Color TINT = new Color(0xECECEC);
    private static final Font BODY = new Font(System.getProperty("os.name", "").startsWith("Windows") ? "Microsoft YaHei UI" : Font.DIALOG, Font.PLAIN, 14);
    private JFrame progress;
    private JFrame checking;
    private JTextArea status;
    private JLabel phase;
    private JProgressBar bar;
    private final List<JLabel> steps = new ArrayList<>();
    private static boolean themeReady;

    enum Stage { PREPARE, DOWNLOAD, INSTALL }

    private static <T> T ui(Callable<T> action) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("图形界面不可用");
        Callable<T> themed = () -> {
            if (!themeReady) {
                javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(new javax.swing.plaf.metal.DefaultMetalTheme() {
                    protected javax.swing.plaf.ColorUIResource getPrimary1() { return new javax.swing.plaf.ColorUIResource(0x707070); }
                    protected javax.swing.plaf.ColorUIResource getPrimary2() { return new javax.swing.plaf.ColorUIResource(0xD0D0D0); }
                    protected javax.swing.plaf.ColorUIResource getPrimary3() { return new javax.swing.plaf.ColorUIResource(0xECECEC); }
                });
                UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
                themeReady = true;
            }
            return action.call();
        };
        if (SwingUtilities.isEventDispatchThread()) return themed.call();
        FutureTask<T> task = new FutureTask<>(themed);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    boolean confirm(Models.Installed installed, Models.Release release) throws Exception {
        return confirm(installed, release, false);
    }

    void checking(Runnable cancel) throws Exception {
        ui(() -> {
            checking = new JFrame("ModpackUpdate · 检查完整包");
            checking.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            checking.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) { cancel.run(); }
            });
            JPanel content = stack();
            content.add(heading("检查完整整合包", "更新接口暂不可用，正在下载并检查备用整合包。"));
            content.add(Box.createVerticalStrut(20));
            content.add(note("可以跳过本次检查；尚未修改游戏文件。"));
            JButton skip = button("跳过检查", false);
            skip.addActionListener(event -> { skip.setEnabled(false); cancel.run(); });
            content.add(Box.createVerticalStrut(20));
            content.add(skip);
            checking.setContentPane(shell(content));
            size(checking, 660);
            checking.setLocationRelativeTo(null);
            checking.setVisible(true);
            return null;
        });
    }

    void closeChecking() throws Exception {
        if (checking != null) ui(() -> { checking.dispose(); checking = null; return null; });
    }

    boolean confirm(Models.Installed installed, Models.Release release, boolean uncertain) throws Exception {
        return ui(() -> {
            JPanel content = stack();
            content.add(heading("发现更新", ""));
            content.add(Box.createVerticalStrut(24));
            JPanel versions = new JPanel(new GridLayout(1, 2, 16, 0));
            versions.setOpaque(false);
            versions.add(versionCard("当前版本", "uninitialized".equals(installed.version) ? "首次安装" : installed.version, false));
            versions.add(versionCard("新版本", release.version(), true));
            content.add(versions);
            content.add(Box.createVerticalStrut(24));
            content.add(label("更新公告", 15, INK, true));
            content.add(Box.createVerticalStrut(10));
            String announcement = release.announcement();
            content.add(scroll(text(announcement == null || announcement.isBlank() ? "暂无更新说明。" : announcement), 640, 190));
            content.add(Box.createVerticalStrut(16));
            content.add(note("更新时会退出游戏，完成后请重新启动。"));
            if (uncertain) {
                content.add(Box.createVerticalStrut(12));
                content.add(note("缺少可比较的发布编号，无法确认新旧顺序；此次安装可能降级，请核对版本与来源。"));
            }
            return dialog(null, "发现更新", content, "更新并退出游戏", "跳过并继续游戏", 720);
        });
    }

    record Selection(List<Models.Change> changes, boolean deleteReplacedMods) {}

    Selection choose(List<Models.Change> changes, Models.Config config) throws Exception {
        List<Models.Change> conflicts = changes.stream().filter(change -> change.conflict() != null).toList();
        if (config.silentUpdate && conflicts.isEmpty()) return new Selection(changes, config.deleteReplacedMods);
        return ui(() -> {
            boolean[] checked = new boolean[conflicts.size()];
            int initiallySelected = 0;
            for (int i = 0; i < conflicts.size(); i++) {
                Models.Change change = conflicts.get(i);
                // Keep newly offered optional files opt-in; other conflicts default to the remote operation.
                checked[i] = change.disable() || !change.target().optional() || change.observedHash() != null;
                if (checked[i]) initiallySelected++;
            }
            AbstractTableModel model = new AbstractTableModel() {
                final String[] columns = {"采用新版操作", "文件", "操作 / 原因"};
                public int getRowCount() { return conflicts.size(); }
                public int getColumnCount() { return columns.length; }
                public String getColumnName(int column) { return columns[column]; }
                public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
                public boolean isCellEditable(int row, int column) { return column == 0; }
                public Object getValueAt(int row, int column) {
                    Models.Change change = conflicts.get(row);
                    return switch (column) {
                        case 0 -> checked[row];
                        case 1 -> change.path();
                        default -> (change.disable() ? "禁用 · " : "安装 · ") + change.conflict();
                    };
                }
                public void setValueAt(Object value, int row, int column) {
                    checked[row] = Boolean.TRUE.equals(value);
                    fireTableCellUpdated(row, column);
                }
            };
            JPanel content = stack();
            content.add(heading("安装确认", "默认采用云端文件；采用新版模组时，一并禁用其他同 ID 本地 JAR。"));
            content.add(Box.createVerticalStrut(22));
            JPanel summary = new JPanel(new GridLayout(1, 3, 12, 0));
            summary.setOpaque(false);
            summary.add(versionCard("全部变更", String.valueOf(changes.size()), false));
            summary.add(versionCard("自动处理", String.valueOf(changes.size() - conflicts.size()), false));
            summary.add(versionCard("需要确认", String.valueOf(conflicts.size()), true));
            content.add(summary);
            content.add(Box.createVerticalStrut(20));
            JTable table = new JTable(model);
            table.setFont(BODY);
            table.setForeground(INK);
            table.setRowHeight(44);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setSelectionBackground(TINT);
            table.setSelectionForeground(INK);
            table.setFillsViewportHeight(true);
            table.getTableHeader().setFont(BODY.deriveFont(Font.BOLD, 12));
            table.getTableHeader().setBackground(TINT);
            table.getTableHeader().setForeground(MUTED);
            table.getTableHeader().setPreferredSize(new Dimension(0, 38));
            table.getTableHeader().setReorderingAllowed(false);
            table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                    super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                    setBorder(new EmptyBorder(0, 12, 0, 12));
                    setToolTipText(String.valueOf(value));
                    return this;
                }
            });
            table.getColumnModel().getColumn(0).setPreferredWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(300);
            table.getColumnModel().getColumn(2).setPreferredWidth(290);
            if (conflicts.isEmpty()) {
                content.add(note("没有文件冲突，可以直接安装。"));
            } else {
                JPanel toolbar = new JPanel(new BorderLayout());
                toolbar.setOpaque(false);
                JLabel selectionCount = label("已选 " + initiallySelected + " / " + conflicts.size() + " 项", 13, MUTED, false);
                model.addTableModelListener(event -> {
                    int count = 0;
                    for (boolean value : checked) if (value) count++;
                    selectionCount.setText("已选 " + count + " / " + conflicts.size() + " 项");
                });
                toolbar.add(selectionCount, BorderLayout.WEST);
                JPanel bulk = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
                bulk.setOpaque(false);
                JButton all = button("全选", false);
                JButton none = button("全不选", false);
                all.addActionListener(event -> { java.util.Arrays.fill(checked, true); model.fireTableDataChanged(); });
                none.addActionListener(event -> { java.util.Arrays.fill(checked, false); model.fireTableDataChanged(); });
                bulk.add(all);
                bulk.add(none);
                toolbar.add(bulk, BorderLayout.EAST);
                content.add(toolbar);
                content.add(Box.createVerticalStrut(10));
                content.add(scroll(table, 740, 240));
                content.add(Box.createVerticalStrut(10));
                content.add(label("取消新版模组可保留旧版；同 ID 模组不能同时启用。可选新文件默认不选。", 12, MUTED, false));
            }
            content.add(Box.createVerticalStrut(18));
            JCheckBox delete = new JCheckBox("删除旧模组（不备份）", config.deleteReplacedMods);
            delete.setFont(BODY.deriveFont(13f));
            delete.setForeground(INK);
            delete.setOpaque(false);
            content.add(delete);
            if (!dialog(progress, "安装确认", content, "开始安装", "取消更新", 840)) return null;
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            List<Models.Change> selected = new ArrayList<>();
            for (Models.Change change : changes) {
                int index = conflicts.indexOf(change);
                if (index < 0 || checked[index]) selected.add(change);
            }
            return new Selection(selected, delete.isSelected());
        });
    }

    void progress(String message) throws Exception { progress(Stage.PREPARE, message, 0, 0); }

    void progress(Stage stage, String message, int completed, int total) throws Exception {
        ui(() -> {
            if (progress == null) {
                progress = new JFrame("ModpackUpdate · 正在更新");
                progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JPanel content = stack();
                content.add(heading("正在更新整合包", ""));
                content.add(Box.createVerticalStrut(28));
                JPanel tracker = new JPanel(new GridLayout(1, 3, 12, 0));
                tracker.setOpaque(false);
                for (String name : List.of("1  准备", "2  下载", "3  安装")) {
                    JLabel step = label(name, 14, MUTED, true);
                    step.setOpaque(true);
                    step.setBorder(new EmptyBorder(14, 16, 14, 16));
                    steps.add(step);
                    tracker.add(step);
                }
                content.add(tracker);
                content.add(Box.createVerticalStrut(28));
                phase = label("", 15, INK, true);
                content.add(phase);
                content.add(Box.createVerticalStrut(12));
                bar = new JProgressBar();
                bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
                bar.setForeground(ACCENT);
                bar.setBackground(LINE);
                bar.setBorderPainted(false);
                bar.setPreferredSize(new Dimension(620, 8));
                content.add(bar);
                content.add(Box.createVerticalStrut(16));
                status = text("");
                status.setBackground(BACKGROUND);
                content.add(scroll(status, 620, 85));
                content.add(Box.createVerticalStrut(20));
                content.add(note("完成后请从启动器重新启动游戏。"));
                progress.setContentPane(shell(content));
                size(progress, 720);
                progress.setLocationRelativeTo(null);
                progress.setVisible(true);
            }
            for (int i = 0; i < steps.size(); i++) {
                steps.get(i).setBackground(i <= stage.ordinal() ? TINT : Color.WHITE);
                steps.get(i).setForeground(i <= stage.ordinal() ? ACCENT : MUTED);
            }
            String title = switch (stage) {
                case PREPARE -> "正在准备更新";
                case DOWNLOAD -> "正在下载文件";
                case INSTALL -> "正在安装更新";
            };
            phase.setText(title + (total > 0 ? "  ·  " + completed + " / " + total + "" : ""));
            bar.setIndeterminate(total <= 0);
            if (total > 0) { bar.setMaximum(total); bar.setValue(completed); }
            status.setText(message);
            status.setCaretPosition(0);
            return null;
        });
    }

    void message(String message, boolean error) throws Exception {
        ui(() -> {
            if (bar != null) {
                bar.setIndeterminate(false);
                if (!error) bar.setValue(bar.getMaximum());
            }
            JPanel content = stack();
            content.add(heading(error ? "更新失败" : "更新完成", ""));
            content.add(Box.createVerticalStrut(24));
            content.add(scroll(text(message), 580, 130));
            dialog(progress, error ? "更新失败" : "更新完成", content, "关闭", null, 660);
            return null;
        });
    }

    void close() throws Exception {
        if (progress != null) ui(() -> {
            bar.setIndeterminate(false);
            progress.dispose();
            progress = null;
            steps.clear();
            return null;
        });
    }

    private static JPanel stack() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JLabel label(String value, int size, Color color, boolean bold) {
        JLabel label = new JLabel();
        // Release metadata and filenames are plain text, never Swing HTML.
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setText(value);
        label.setFont(BODY.deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    private static JPanel heading(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        panel.add(label(title, 24, INK, true), BorderLayout.NORTH);
        if (!subtitle.isBlank()) panel.add(label(subtitle, 13, MUTED, false), BorderLayout.CENTER);
        return panel;
    }

    private static JPanel versionCard(String title, String value, boolean accent) {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(Color.WHITE);
        card.setBorder(new EmptyBorder(14, 16, 14, 16));
        card.add(label(title, 12, MUTED, false), BorderLayout.NORTH);
        JLabel version = label(value, 20, INK, true);
        version.setToolTipText(value);
        card.add(version, BorderLayout.CENTER);
        return card;
    }

    private static JTextArea text(String value) {
        JTextArea text = new JTextArea(value);
        text.setFont(BODY);
        text.setForeground(INK);
        text.setBackground(Color.WHITE);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setMargin(new Insets(14, 16, 14, 16));
        text.setCaretPosition(0);
        return text;
    }

    private static JScrollPane scroll(JComponent component, int width, int height) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createLineBorder(LINE));
        scroll.setPreferredSize(new Dimension(width, height));
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private static JTextArea note(String value) {
        JTextArea note = text(value);
        note.setFont(BODY.deriveFont(12f));
        note.setForeground(MUTED);
        note.setBackground(TINT);
        return note;
    }

    private static JButton button(String title, boolean primary) {
        JButton button = new JButton(title) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hover = getModel().isRollover();
                g.setColor(primary ? (getModel().isPressed() ? new Color(0x555555) : hover ? new Color(0x424242) : ACCENT)
                        : (getModel().isPressed() ? TINT : hover ? new Color(0xF9F9F9) : Color.WHITE));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g.setColor(primary ? ACCENT : LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                if (isFocusOwner()) {
                    g.setColor(primary ? Color.WHITE : INK);
                    g.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 5, 5);
                }
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setFont(BODY.deriveFont(Font.BOLD, 13));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setBackground(primary ? ACCENT : Color.WHITE);
        button.setOpaque(false);
        button.setBorder(new EmptyBorder(10, 18, 10, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static JPanel shell(JPanel content) {
        JPanel shell = new JPanel(new BorderLayout(0, 20));
        shell.setBackground(BACKGROUND);
        shell.setBorder(new EmptyBorder(24, 28, 24, 28));
        // BoxLayout children share a left edge, including text and scroll panes.
        for (Component component : content.getComponents()) {
            if (component instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        shell.add(content, BorderLayout.CENTER);
        return shell;
    }

    private static boolean dialog(Window owner, String title, JPanel content, String accept, String cancel, int width) {
        JDialog dialog = new JDialog(owner, "ModpackUpdate · " + title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        boolean[] accepted = {false};
        JPanel shell = shell(content);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        if (cancel != null) {
            JButton secondary = button(cancel, false);
            secondary.addActionListener(event -> dialog.dispose());
            actions.add(secondary);
        }
        JButton primary = button(accept, true);
        primary.addActionListener(event -> { accepted[0] = true; dialog.dispose(); });
        actions.add(primary);
        shell.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(shell);
        dialog.getRootPane().setDefaultButton(primary);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        size(dialog, width);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return accepted[0];
    }

    private static void size(Window window, int width) {
        window.pack();
        Rectangle screen = window.getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(window.getGraphicsConfiguration());
        int availableWidth = screen.width - insets.left - insets.right;
        int availableHeight = screen.height - insets.top - insets.bottom;
        window.setSize(Math.min(width, availableWidth), Math.min(window.getHeight(), availableHeight));
        window.setMinimumSize(new Dimension(Math.min(width, availableWidth), Math.min(window.getHeight(), availableHeight)));
    }
}
