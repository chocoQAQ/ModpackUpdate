package dev.modpackupdate.updater;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

final class UpdateUi {
    private JFrame progress;
    private JLabel status;
    private static <T> T ui(Callable<T> action) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("图形界面不可用");
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    boolean confirm(Models.Installed installed, Models.Release release) throws Exception {
        return ui(() -> {
            JTextArea announcement = new JTextArea(release.announcement() == null ? "" : release.announcement());
            announcement.setEditable(false);
            announcement.setLineWrap(true);
            announcement.setWrapStyleWord(true);
            JPanel panel = new JPanel(new BorderLayout(0, 12));
            panel.add(new JLabel(installed.version + " → " + release.version()), BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(announcement);
            scroll.setPreferredSize(new Dimension(600, 300));
            panel.add(scroll);
            panel.add(new JLabel("更新将关闭本次游戏，完成后请从启动器重新启动。"), BorderLayout.SOUTH);
            return JOptionPane.showOptionDialog(null, panel, "ModpackUpdate · 发现更新",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                    new String[]{"更新并退出游戏", "跳过并继续游戏"}, "更新并退出游戏") == 0;
        });
    }
    record Selection(List<Models.Change> changes, boolean deleteReplacedMods) {}
    Selection choose(List<Models.Change> changes, Models.Config config) throws Exception {
        List<Models.Change> conflicts = changes.stream().filter(change -> change.conflict() != null).toList();
        if (config.silentUpdate && conflicts.isEmpty()) return new Selection(changes, config.deleteReplacedMods);
        return ui(() -> {
            boolean[] checked = new boolean[conflicts.size()];
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
                public void setValueAt(Object value, int row, int column) { checked[row] = (Boolean) value; fireTableCellUpdated(row, column); }
            };
            JTable table = new JTable(model);
            table.setRowHeight(28);
            table.getColumnModel().getColumn(0).setPreferredWidth(110);
            table.getColumnModel().getColumn(1).setPreferredWidth(320);
            table.getColumnModel().getColumn(2).setPreferredWidth(280);
            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.add(new JLabel("共 " + changes.size() + " 项变更，" + conflicts.size() + " 项需要选择。未勾选的冲突保留本地文件。"), BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(760, 320));
            panel.add(scroll);
            JPanel controls = new JPanel();
            JButton all = new JButton("全选");
            JButton none = new JButton("全不选");
            all.addActionListener(event -> { java.util.Arrays.fill(checked, true); model.fireTableDataChanged(); });
            none.addActionListener(event -> { java.util.Arrays.fill(checked, false); model.fireTableDataChanged(); });
            JCheckBox delete = new JCheckBox("直接删除被替换的旧模组，不保留备份", config.deleteReplacedMods);
            controls.add(all); controls.add(none); controls.add(delete);
            panel.add(controls, BorderLayout.SOUTH);
            int result = JOptionPane.showOptionDialog(progress, panel, "ModpackUpdate · 安装确认",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                    new String[]{"开始安装", "取消更新"}, "开始安装");
            if (result != 0) return null;
            List<Models.Change> selected = new ArrayList<>();
            for (Models.Change change : changes) {
                int index = conflicts.indexOf(change);
                if (index < 0 || checked[index]) selected.add(change);
            }
            return new Selection(selected, delete.isSelected());
        });
    }
    void progress(String message) throws Exception {
        ui(() -> {
            if (progress == null) {
                progress = new JFrame("ModpackUpdate");
                progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                status = new JLabel();
                status.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
                progress.add(status);
                progress.setSize(700, 130);
                progress.setLocationRelativeTo(null);
                progress.setVisible(true);
            }
            status.setText(message);
            return null;
        });
    }
    void message(String message, boolean error) throws Exception {
        ui(() -> { JOptionPane.showMessageDialog(progress, message, "ModpackUpdate",
                error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE); return null; });
    }
    void close() throws Exception { if (progress != null) ui(() -> { progress.dispose(); return null; }); }
}
