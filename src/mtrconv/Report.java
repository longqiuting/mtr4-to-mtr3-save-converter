package mtrconv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Report {

    public enum Status {OK, SKIP, FAIL}

    public final Path source;
    public final Path output;
    public String sourceVersion = "未检测到";
    public String targetVersion = "未检测到";
    public final List<String> versionNotes = new ArrayList<>();
    /** Multi-line explanation of the root cause / key fix, printed first. */
    public String rootCause = "";
    public final Map<String, int[]> counts = new LinkedHashMap<>();
    public final List<String> details = new ArrayList<>();
    public final List<String> losses = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final long startedAt = System.currentTimeMillis();

    public Report(Path source, Path output) {
        this.source = source;
        this.output = output;
    }

    public void count(String kind, Status status) {
        int[] c = counts.computeIfAbsent(kind, k -> new int[3]);
        c[status.ordinal()]++;
    }

    public void detail(String line) {
        details.add(line);
    }

    public void loss(String line) {
        if (!losses.contains(line)) losses.add(line);
    }

    public void warn(String line) {
        warnings.add(line);
    }

    public boolean anyFailure() {
        for (int[] c : counts.values()) if (c[Status.FAIL.ordinal()] > 0) return true;
        return false;
    }

    public int total(Status status) {
        int n = 0;
        for (int[] c : counts.values()) n += c[status.ordinal()];
        return n;
    }

    private static final String[] KIND_LABELS = {
            "stations", "platforms", "routes", "depots", "sidings", "rails", "lifts", "signal-blocks"
    };

    private static String label(String kind) {
        switch (kind) {
            case "stations": return "车站 stations";
            case "platforms": return "站台 platforms";
            case "routes": return "线路 routes";
            case "depots": return "车厂 depots";
            case "sidings": return "侧线 sidings";
            case "rails": return "轨道 rails";
            case "lifts": return "电梯 lifts";
            case "signal-blocks": return "信号 blocks";
            default: return kind;
        }
    }

    /** Pads by display width so CJK labels still line up in a monospace font. */
    private static String pad(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            width += (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFF60) ? 2 : 1;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = width; i < 26; i++) sb.append(' ');
        return sb.toString();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        String line = "==================================================================";
        sb.append("MTR4 → MTR3 存档转换报告\n").append(line).append('\n');
        sb.append("生成时间  : ").append(fmt.format(new Date())).append('\n');
        sb.append("耗时      : ").append(System.currentTimeMillis() - startedAt).append(" ms\n");
        sb.append("源存档    : ").append(source).append('\n');
        sb.append("输出存档  : ").append(output).append('\n');
        sb.append("源 MTR4   : ").append(sourceVersion).append('\n');
        sb.append("目标 MTR3 : ").append(targetVersion).append('\n');
        for (String note : versionNotes) sb.append("版本提示  : ").append(note).append('\n');

        sb.append('\n').append("== 零、根因与关键修复 ==").append('\n');
        sb.append(rootCause.isEmpty() ? "（无）\n" : rootCause + "\n");

        sb.append('\n').append("== 一、统计 ==").append('\n');
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            sb.append(pad(label(e.getKey()))).append(" 成功 ").append(c[0])
                    .append("    跳过 ").append(c[Status.SKIP.ordinal()])
                    .append("    失败 ").append(c[Status.FAIL.ordinal()]).append('\n');
        }
        sb.append(pad("合计")).append(" 成功 ").append(total(Status.OK))
                .append("    跳过 ").append(total(Status.SKIP))
                .append("    失败 ").append(total(Status.FAIL)).append('\n');

        sb.append('\n').append("== 二、警告 ==").append('\n');
        if (warnings.isEmpty()) sb.append("（无）\n");
        for (String w : warnings) sb.append("! ").append(w).append('\n');

        sb.append('\n').append("== 三、丢失项（MTR3 数据模型无对应字段）==").append('\n');
        if (losses.isEmpty()) sb.append("（无）\n");
        for (String l : losses) sb.append("- ").append(l).append('\n');

        sb.append('\n').append("== 四、明细 ==").append('\n');
        for (String d : details) sb.append(d).append('\n');
        return sb.toString();
    }

    public Path save(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, render().getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
