package mtrconv;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class Main extends JFrame {

    public static final String VERSION = "0.2.0";
    public static final String TITLE = "MTR4 → MTR3 存档转换器 " + VERSION;

    public static void main(String[] args) {
        if (args.length > 0) {
            try {
                int code = runCli(args);
                if (code != Integer.MIN_VALUE) System.exit(code);
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
                System.exit(2);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the cross-platform L&F
            }
            new Main().setVisible(true);
        });
    }

    /* ================================================================== CLI */

    private static int runCli(String[] args) throws Exception {
        switch (args[0]) {
            case "--help":
            case "-h":
                System.out.println("""
                        MTR4 → MTR3 存档转换器 %s

                        用法:
                          java -jar MTR4toMTR3.jar
                              图形界面（双击也可以）

                          java -jar MTR4toMTR3.jar --cli <源存档目录> <目标实例目录> [输出存档名]
                              例: --cli ".../Tianyicraft/saves/新的世界" ".../1.20.1-Fabric 0.19.3"

                          java -jar MTR4toMTR3.jar --selftest <MTR3存档或mtr目录>
                              拿真实 MTR3 轨道数据校验内置几何算法

                          java -jar MTR4toMTR3.jar --verify <存档目录>
                              只读校验：逐键比对 MTR3 原生 schema + 检查 data/mtr_train_data.dat
                              （可以指向 MTR3 自己写的存档，验证检查器本身是否可靠）

                        说明:
                          * 源存档只读，绝不写入
                          * 输出始终写到 <目标实例>/saves/<名字>，已存在则中止
                          * 转换前把原始 mtr/ 备份到输出存档的 .mtr4-backup/
                        """.formatted(VERSION));
                return Integer.MIN_VALUE;
            case "--selftest": {
                SelfTest.main(new String[]{args[1]});
                return Integer.MIN_VALUE;
            }
            case "--verify": {
                if (args.length < 2) {
                    System.err.println("用法: --verify <存档目录>");
                    return 2;
                }
                return Verify.run(Paths.get(args[1])) ? 0 : 1;
            }
            case "--cli": {
                if (args.length < 3) {
                    System.err.println("用法: --cli <源存档目录> <目标实例目录> [输出存档名]");
                    return 2;
                }
                Path src = Paths.get(args[1]);
                Path target = Paths.get(args[2]);
                String name = args.length > 3 ? args[3] : null;
                Report report = Converter.run(src, target, name, System.out::println);
                System.out.println();
                System.out.println(report.render());
                return report.anyFailure() ? 1 : 0;
            }
            default:
                System.err.println("未知参数: " + args[0] + "（--help 查看用法）");
                return 2;
        }
    }

    /* ================================================================== GUI */

    private final JTextField sourceField = new JTextField();
    private final JTextField targetField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JTextArea output = new JTextArea();
    private final JLabel status = new JLabel("就绪");
    private final JButton convertButton = new JButton("开始转换");

    private Main() {
        super(TITLE);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(980, 720);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.add(buildHeader(), BorderLayout.NORTH);
        header.add(buildForm(), BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);
        root.add(buildReport(), BorderLayout.CENTER);
        setContentPane(root);
    }

    private JComponent buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel(TITLE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel warning = new JLabel("<html><body style='width:820px'><b>安全策略</b>：源存档只读 → "
                + "先整份复制到目标实例 saves/ → 原始 mtr/ 备份到 .mtr4-backup/ → 只在副本上转换。"
                + "目标目录已存在则直接中止。</body></html>");
        warning.setForeground(new Color(0x8A4B00));
        panel.add(title, BorderLayout.NORTH);
        panel.add(warning, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        form.add(row("源存档目录（MTR4，只读）", sourceField, button("浏览…", e -> browseSource())));
        form.add(row("目标实例目录（MTR3）", targetField, button("浏览…", e -> browseTarget())));
        form.add(row("输出存档名", nameField, null));
        form.add(Box.createVerticalStrut(8));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton preflight = new JButton("预检");
        preflight.addActionListener(this::onPreflight);
        convertButton.addActionListener(this::onConvert);
        JButton clear = new JButton("清空日志");
        clear.addActionListener(e -> output.setText(""));
        actions.add(preflight);
        actions.add(convertButton);
        actions.add(clear);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        form.add(actions);

        JTextArea hint = new JTextArea("提示：输出会写到 <目标实例>/saves/<名字>。"
                + "转换完成后请先用 MTR3 打开这个新存档确认，确认前不要用 MTR3 打开源存档。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setOpaque(false);
        hint.setForeground(new Color(0x555555));
        hint.setBorder(new EmptyBorder(6, 2, 0, 2));
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        form.add(hint);
        return form;
    }

    private JComponent row(String label, JTextField field, JComponent extra) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(196, 24));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        if (extra != null) p.add(extra, BorderLayout.EAST);
        p.setBorder(new EmptyBorder(3, 0, 3, 0));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private JComponent buildReport() {
        output.setEditable(false);
        output.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        output.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(output);
        scroll.setMinimumSize(new Dimension(600, 200));

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        JButton export = new JButton("导出报告…");
        export.addActionListener(this::onExport);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(export, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JButton button(String text, java.awt.event.ActionListener listener) {
        JButton b = new JButton(text);
        b.addActionListener(listener);
        return b;
    }

    private void browseSource() {
        JFileChooser chooser = new JFileChooser(defaultSavesRoot().toFile());
        chooser.setDialogTitle("选择 MTR4 源存档目录（saves 下的世界文件夹）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sourceField.setText(chooser.getSelectedFile().getAbsolutePath());
            if (nameField.getText().trim().isEmpty()) {
                nameField.setText(chooser.getSelectedFile().getName() + "-MTR3");
            }
        }
    }

    private void browseTarget() {
        JFileChooser chooser = new JFileChooser(defaultVersionsRoot().toFile());
        chooser.setDialogTitle("选择目标 MTR3 实例目录（含 mods/ 和 saves/）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private Path defaultSavesRoot() {
        String home = System.getProperty("user.home", ".");
        Path pcl = Paths.get(home, "Desktop", "PCL", ".minecraft", "versions");
        return Files.isDirectory(pcl) ? pcl : Paths.get(home);
    }

    private Path defaultVersionsRoot() {
        return defaultSavesRoot();
    }

    private void onPreflight(ActionEvent e) {
        Path src = Paths.get(sourceField.getText().trim());
        Path target = Paths.get(targetField.getText().trim());
        output.setText("");
        try {
            if (!Files.isDirectory(src.resolve("mtr"))) {
                append("源目录里没有 mtr/：" + src + "\n");
                return;
            }
            append("源存档: " + src + "\n");
            for (String kind : new String[]{"stations", "platforms", "routes", "depots", "sidings", "rails", "lifts", "signal-blocks"}) {
                int n = countKind(src.resolve("mtr"), kind);
                if (n > 0) append(String.format("  %-16s %d%n", kind, n));
            }
            Versions.Mod targetMod = Versions.findMtr(target);
            append("目标 MTR3: " + (targetMod == null ? "未检测到 id=mtr 的模组！" : targetMod.describe()) + "\n");
            String name = nameField.getText().trim();
            if (name.isEmpty()) name = src.getFileName().toString() + "-MTR3";
            Path out = target.resolve("saves").resolve(name);
            append("输出位置: " + out + (Files.exists(out) ? "   [已存在，转换会中止]" : "   [可用]") + "\n");
            append("\n预检不写任何文件。点「开始转换」执行。\n");
            status.setText("预检完成");
        } catch (Exception ex) {
            append("预检失败: " + ex + "\n");
        }
    }

    private int countKind(Path mtrRoot, String kind) {
        int n = 0;
        try (var stream = Files.walk(mtrRoot)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String s = p.toString().replace('\\', '/');
                if (s.contains("/" + kind + "/")) n++;
            }
        } catch (IOException ignored) {
            // ignore
        }
        return n;
    }

    private void onConvert(ActionEvent e) {
        Path src = Paths.get(sourceField.getText().trim());
        Path target = Paths.get(targetField.getText().trim());
        String name = nameField.getText().trim();
        if (src.toString().isEmpty() || target.toString().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先选择源存档和目标实例目录。", TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        convertButton.setEnabled(false);
        output.setText("");
        status.setText("转换中…");
        SwingWorker<Report, String> worker = new SwingWorker<>() {
            @Override
            protected Report doInBackground() throws Exception {
                return Converter.run(src, target, name.isEmpty() ? null : name, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String s : chunks) append(s + "\n");
            }

            @Override
            protected void done() {
                convertButton.setEnabled(true);
                try {
                    Report report = get();
                    append("\n" + report.render());
                    status.setText("完成：成功 " + report.total(Report.Status.OK)
                            + " / 跳过 " + report.total(Report.Status.SKIP)
                            + " / 失败 " + report.total(Report.Status.FAIL));
                    lastReport = report;
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    append("\n转换中止: " + cause.getMessage() + "\n");
                    status.setText("转换中止");
                }
            }
        };
        worker.execute();
    }

    private Report lastReport;

    private void append(String text) {
        output.append(text);
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void onExport(ActionEvent e) {
        if (lastReport == null) {
            JOptionPane.showMessageDialog(this, "还没有可导出的报告，先跑一次转换。", TITLE, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("MTR4-to-MTR3-report.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.write(chooser.getSelectedFile().toPath(),
                        lastReport.render().getBytes(StandardCharsets.UTF_8));
                status.setText("报告已导出: " + chooser.getSelectedFile());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "导出失败: " + ex, TITLE, JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
