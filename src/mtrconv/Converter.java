package mtrconv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The conversion engine.
 *
 * <p>Hard safety rules implemented here:</p>
 * <ol>
 *   <li>The source save is only ever read — never written, never renamed.</li>
 *   <li>Everything happens inside a freshly created copy under
 *       {@code <targetInstance>/saves/<name>}; an existing directory is never reused.</li>
 *   <li>The original {@code mtr/} tree is copied verbatim into
 *       {@code <output>/.mtr4-backup/} (with a SHA-256 manifest) before anything is written,
 *       so a bad conversion can be rolled back without touching the source.</li>
 * </ol>
 */
public final class Converter {

    public interface Progress {
        void log(String message);
    }

    public static final String BACKUP_DIR = ".mtr4-backup";
    public static final String REPORT_FILE = "MTR4-to-MTR3-report.txt";

    /** Absolute root of the world currently being written; every write is checked against it. */
    private static volatile Path OUTPUT_ROOT;

    private static final String[] KINDS = {
            "stations", "platforms", "routes", "depots", "sidings", "rails", "lifts", "signal-blocks"
    };

    /** Printed at the top of every report: why the previous build produced an "empty" world. */
    static final String ROOT_CAUSE =
            "上一版产物「能进游戏但轨道/车站/车厂全消失」的根因（已用同实例两个正常 MTR3 存档逐项对照 + MTR3 3.6.4.2 字节码确认）：\n"
          + "  · MTR3 的 RailwayData.getInstance(world) 取的是 PersistentState 名为 \"mtr_train_data\" 的数据，\n"
          + "    该 state 的 read(CompoundTag) 是唯一会调用 RailwayData.load(tag) → RailwayDataFileSaveModule.load() 的入口，\n"
          + "    而后者才是唯一会去列 mtr/<ns>/<dim>/<kind>/<bucket>/<id> 的代码。\n"
          + "  · 当 data/mtr_train_data.dat 不存在时，Minecraft 直接用 supplier 新建一个空 state，不调用 read()，\n"
          + "    于是 load() 根本不执行 —— 文件树再正确也不会被读，日志里连 \"data successfully loaded\" 都不会出现。\n"
          + "  · MTR4 从不写这个文件（它用 data/mtr.dat，键是 route_ids_with_disabled_announcements 之类），\n"
          + "    所以上一版产物缺它：MTR3 以空存档启动，进游戏后一切为空，随后存档时还写回了一个空的 state。\n"
          + "  · 修复：按维度写出 data/mtr_train_data.dat = {DataVersion:int, data:{use_time_and_wind_sync:0}}，\n"
          + "    故意不写 raw_message_pack —— 该键一旦存在，MTR3 会改为只读内联快照、同样忽略 mtr/ 文件。\n"
          + "  · 同一轮里还对齐了 msgpack 线上类型：rail_length / acceleration_constant 改为 float32（MTR3 原生写 f32），\n"
          + "    depots 去掉 MTR3 不写的 last_deployed；回读校验改为逐键比对 MTR3 原生 schema（缺键/多键/类型不符都会报）。";

    private Converter() {
    }

    /* ================================================================ entry */

    public static Report run(Path source, Path targetInstance, String worldName, Progress progress) throws Exception {
        Path mtr4Root = source.resolve("mtr");
        if (!Files.isDirectory(mtr4Root)) {
            throw new IOException("源存档里没有 mtr/ 目录，这不像是 MTR 存档：" + source);
        }
        Path savesDir = targetInstance.resolve("saves");
        Files.createDirectories(savesDir);

        String baseName = source.getFileName().toString();
        String name = worldName == null || worldName.trim().isEmpty() ? baseName + "-MTR3" : worldName.trim();
        Path output = savesDir.resolve(name);
        if (Files.exists(output)) {
            throw new IOException("输出目录已存在，为避免覆盖已中止：" + output + "\n请换一个名字，或先手动删除该目录。");
        }
        OUTPUT_ROOT = output.toAbsolutePath().normalize();

        Report report = new Report(source, output);
        report.rootCause = ROOT_CAUSE;

        Versions.Mod sourceMod = Versions.findMtr(Versions.findInstanceRoot(source));
        Versions.Mod targetMod = Versions.findMtr(targetInstance);
        if (sourceMod != null) {
            report.sourceVersion = sourceMod.describe();
            if (!Versions.isKnown(Versions.KNOWN_SOURCE, sourceMod.version)) {
                report.versionNotes.add("源 MTR4 版本 " + sourceMod.version + " 不在已验证白名单 "
                        + String.join("/", Versions.KNOWN_SOURCE) + " 内，字段映射按 4.0.x 处理，请核对报告。");
            }
        } else {
            report.sourceVersion = "未在源实例 mods/ 中找到 id=mtr 的模组（按 4.0.x 字段映射处理）";
        }
        if (targetMod == null) {
            throw new IOException("目标实例没有检测到 MTR3（mods/ 下没有 id=mtr 的模组）：" + targetInstance);
        }
        report.targetVersion = targetMod.describe();
        if (targetMod.major >= 4) {
            throw new IOException("目标实例装的是 MTR4（" + targetMod.version + "），不是 MTR3，已中止。");
        }
        if (!Versions.isKnown(Versions.KNOWN_TARGET, targetMod.version)) {
            report.versionNotes.add("目标 MTR3 版本 " + targetMod.version + " 不在已验证白名单 "
                    + String.join("/", Versions.KNOWN_TARGET) + " 内，输出按 3.6.4.2 的 schema 写出。");
        }

        progress.log("源存档    : " + source);
        progress.log("输出存档  : " + output);
        progress.log("源 MTR4   : " + report.sourceVersion);
        progress.log("目标 MTR3 : " + report.targetVersion);

        progress.log("复制世界（不含 mtr/，源目录只读）...");
        copyWorld(source, output);

        progress.log("备份原始 mtr/ → " + BACKUP_DIR + "/ ...");
        backupMtr(mtr4Root, output.resolve(BACKUP_DIR));

        progress.log("转换 mtr/ 数据树 ...");
        convertTree(mtr4Root, output.resolve("mtr"), report, progress);

        progress.log("写入 data/mtr_train_data.dat（MTR3 扫描 mtr/ 的触发文件）...");
        writeTrainDataStates(output, output.resolve("mtr"), report, progress);

        progress.log("回读校验 ...");
        verifyTree(output, output.resolve("mtr"), report);

        report.save(output.resolve(REPORT_FILE));
        progress.log("报告已写入: " + output.resolve(REPORT_FILE));
        return report;
    }

    /* ================================================================ copy */

    private static void copyWorld(Path source, Path output) throws IOException {
        Files.createDirectories(output);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(source)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                if (dir.getParent().equals(source) && (name.equals("mtr") || name.equals(BACKUP_DIR))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(output.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                Path target = output.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void backupMtr(Path mtr4Root, Path backupRoot) throws IOException {
        Path target = backupRoot.resolve("mtr");
        Files.createDirectories(target);
        List<String> manifest = new ArrayList<>();
        manifest.add("# MTR4 原始 mtr/ 目录的逐文件 SHA-256（转换前备份，回滚用）");
        try (var stream = Files.walk(mtr4Root)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile).sorted()::iterator) {
                Path dest = target.resolve(mtr4Root.relativize(p).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                manifest.add(sha256(p) + "  " + mtr4Root.relativize(p).toString().replace('\\', '/'));
            }
        }
        // LF endings + two-space separator so `sha256sum -c MANIFEST.sha256` works
        Files.writeString(backupRoot.resolve("MANIFEST.sha256"), String.join("\n", manifest) + "\n",
                StandardCharsetsHolder.UTF8);
        Files.writeString(backupRoot.resolve("README.txt"),
                "这里是从源存档只读复制过来的原始 MTR4 mtr/ 数据树（转换前状态）。\n"
                        + "如需回滚：删除输出存档的 mtr/ 目录，把这里的 mtr/ 复制回去即可。\n"
                        + "注意：MTR3 无法读取这些文件；回滚只对「想重新转换」有意义。\n", StandardCharsetsHolder.UTF8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /* ============================================================ train data */

    /**
     * Writes {@code data/mtr_train_data.dat} (per dimension) into the output world.
     *
     * <p>This is not cosmetic: MTR3 only reaches its {@code mtr/} file tree through
     * {@code RailwayData.load(CompoundTag)}, and that method is only ever invoked by the
     * {@code PersistentState} reader of the state named {@code mtr_train_data}. When the
     * file is absent, Minecraft builds the state from the supplier without calling the
     * reader, so MTR3 starts empty and ignores every converted file. MTR4 never wrote
     * that file (it uses {@code data/mtr.dat}), which is why the first conversion looked
     * like "everything disappeared".</p>
     */
    private static void writeTrainDataStates(Path output, Path outMtr, Report report, Progress progress) throws IOException {
        int dataVersion = Nbt.readDataVersion(output.resolve("level.dat"));

        java.util.LinkedHashSet<String> worldDirs = new java.util.LinkedHashSet<>();
        worldDirs.add("");                                   // overworld
        for (Path ns : listDirs(outMtr)) {
            for (Path dim : listDirs(ns)) {
                worldDirs.add(worldDirFor(ns.getFileName().toString(), dim.getFileName().toString()));
            }
        }

        for (String rel : worldDirs) {
            Path worldDir = rel.isEmpty() ? output : output.resolve(rel);
            Path stateDir = worldDir.resolve("data");
            Path target = stateDir.resolve("mtr_train_data.dat");
            guard(output, target);

            if (Files.exists(target)) {
                Path backup = output.resolve(BACKUP_DIR).resolve("data")
                        .resolve(rel.isEmpty() ? "overworld" : rel.replace('\\', '/').replace('/', '_'))
                        .resolve("mtr_train_data.dat");
                guard(output, backup);
                Files.createDirectories(backup.getParent());
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                report.detail("[备份] 源世界已有的 " + (rel.isEmpty() ? "" : rel + "/")
                        + "data/mtr_train_data.dat → " + BACKUP_DIR + "/data/（内容被 MTR3 以文件树为准替换）");
            }

            Nbt.writeTrainData(target, dataVersion, false);
            report.count("mtr_train_data", Report.Status.OK);
            String keys = String.join(",", Nbt.rootKeys(target));
            report.detail("[写入] " + (rel.isEmpty() ? "" : rel + "/") + "data/mtr_train_data.dat  DataVersion="
                    + dataVersion + "  根键=" + keys
                    + "  raw_message_pack=" + Nbt.hasRawMessagePack(target) + "（必须 false，否则 MTR3 只读内联快照）");
        }
        progress.log("mtr_train_data.dat: " + worldDirs.size() + " 个维度目录，DataVersion=" + dataVersion);
    }

    /** Maps {@code mtr/<namespace>/<dimension>} to the folder Minecraft stores that dimension's data in. */
    static String worldDirFor(String namespace, String dimension) {
        if ("minecraft".equals(namespace)) {
            switch (dimension) {
                case "overworld":
                    return "";
                case "the_nether":
                    return "DIM-1";
                case "the_end":
                    return "DIM1";
                default:
                    break;
            }
        }
        return "dimensions/" + namespace + "/" + dimension;
    }

    /** Every write must land inside the freshly created output world. */
    private static void guard(Path target) throws IOException {
        guard(OUTPUT_ROOT, target);
    }

    /** Every write must land inside the freshly created output world. */
    private static void guard(Path output, Path target) throws IOException {
        Path root = output.toAbsolutePath().normalize();
        Path p = target.toAbsolutePath().normalize();
        if (!p.startsWith(root)) {
            throw new IOException("拒绝写入输出目录之外的路径：" + target);
        }
    }

    /* ================================================================ tree */

    private static void convertTree(Path mtr4Root, Path outMtr, Report report, Progress progress) throws IOException {
        for (Path namespace : listDirs(mtr4Root)) {
            for (Path dim : listDirs(namespace)) {
                String ns = namespace.getFileName().toString();
                String dimName = dim.getFileName().toString();
                for (Path kindDir : listDirs(dim)) {
                    String kind = kindDir.getFileName().toString();
                    Path outDim = outMtr.resolve(ns).resolve(dimName);
                    switch (kind) {
                        case "stations":
                            convertStations(kindDir, outDim, report, progress);
                            break;
                        case "platforms":
                            convertPlatforms(kindDir, outDim, report, progress);
                            break;
                        case "routes":
                            convertRoutes(kindDir, outDim, report, progress);
                            break;
                        case "depots":
                            convertDepots(kindDir, outDim, report, progress);
                            break;
                        case "sidings":
                            convertSidings(kindDir, outDim, report, progress);
                            break;
                        case "rails":
                            convertRails(kindDir, outDim, report, progress);
                            break;
                        case "settings":
                            report.count(kind, Report.Status.SKIP);
                            report.detail("[跳过] " + ns + "/" + dimName + "/settings（MTR4 专有，MTR3 无此数据）");
                            report.loss("settings{lastSimulationMillis}：纯运行时状态，无意义");
                            break;
                        case "lifts":
                        case "signal-blocks": {
                            int copied = copyRaw(kindDir, outDim.resolve(kind), report, kind, ns + "/" + dimName);
                            if (copied > 0) {
                                report.warn(ns + "/" + dimName + "/" + kind + "：" + copied
                                        + " 个文件——MVP 未实现该类型的字段映射，已原样复制，"
                                        + "MTR3 读取时可能取默认值，建议转换后在游戏内复查/重建。");
                            }
                            break;
                        }
                        default: {
                            int copied = copyRaw(kindDir, outDim.resolve(kind), report, kind, ns + "/" + dimName);
                            if (copied == 0) report.count(kind, Report.Status.SKIP);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static int copyRaw(Path srcDir, Path dstDir, Report report, String kind, String where) throws IOException {
        int copied = 0;
        for (String file : Versions.filesUnder(srcDir)) {
            Path p = Paths.get(file);
            Path target = dstDir.resolve(srcDir.relativize(p).toString());
            guard(target);
            Files.createDirectories(target.getParent());
            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            report.count(kind, Report.Status.OK);
            report.detail("[原样] " + where + "/" + kind + "/" + srcDir.relativize(p).toString().replace('\\', '/'));
            copied++;
        }
        return copied;
    }

    /* ============================================================ converters */

    private static void convertStations(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long id = Util.getLong(src, "id", 0);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("transport_mode", Util.getString(src, "transportMode", "TRAIN"));
                out.put("name", Util.getString(src, "name", ""));
                out.put("color", Util.getLong(src, "color", 0));

                long[] p1 = Util.position(src, "position1");
                long[] p2 = Util.position(src, "position2");
                out.put("x_min", Math.min(p1[0], p2[0]));
                out.put("z_min", Math.min(p1[2], p2[2]));
                out.put("x_max", Math.max(p1[0], p2[0]));
                out.put("z_max", Math.max(p1[2], p2[2]));
                out.put("zone", Util.getInt(src, "zone1", 0));

                Map<String, Object> exits = new LinkedHashMap<>();
                Object rawExits = src.get("exits");
                if (rawExits instanceof Map) {
                    for (Map.Entry<String, Object> e : MsgPack.cast(rawExits).entrySet()) {
                        exits.put(e.getKey(), toList(e.getValue()));
                    }
                } else if (rawExits instanceof List) {
                    for (Object o : (List<?>) rawExits) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> entry = MsgPack.cast(o);
                        exits.put(Util.getString(entry, "name", ""), toList(entry.get("destinations")));
                    }
                }
                out.put("exits", exits);

                if (p1[1] != p2[1]) {
                    report.loss("车站 \"" + out.get("name") + "\"：MTR4 的 y 范围被折叠为 2D 矩形（MTR3 车站无高度）");
                }
                Util.getInt(src, "zone2", 0);
                if (Util.getInt(src, "zone2", 0) != 0 || Util.getInt(src, "zone3", 0) != 0) {
                    report.loss("车站 \"" + out.get("name") + "\"：zone2/zone3 丢弃，只保留 zone1="
                            + out.get("zone"));
                }

                Path written = writeObject(outDim.resolve("stations"), id, out);
                report.count("stations", Report.Status.OK);
                report.detail("[OK]   stations " + rel + "  \"" + out.get("name") + "\" → "
                        + outDim.resolve("stations").relativize(written).toString().replace('\\', '/'));
            } catch (Exception e) {
                report.count("stations", Report.Status.FAIL);
                report.detail("[失败] stations " + rel + " : " + e);
            }
        }
    }

    private static void convertPlatforms(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long id = Util.getLong(src, "id", 0);
                long[] p1 = Util.position(src, "position1");
                long[] p2 = Util.position(src, "position2");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("transport_mode", Util.getString(src, "transportMode", "TRAIN"));
                out.put("name", Util.getString(src, "name", ""));
                out.put("color", Util.getLong(src, "color", 0));
                out.put("pos_1", Util.pack(p1[0], p1[1], p1[2]));
                out.put("pos_2", Util.pack(p2[0], p2[1], p2[2]));
                double dwellRaw = Util.getDouble(src, "dwellTime", 10000);
                int dwell = (int) Math.round(dwellRaw / 500.0);
                if (Math.abs(dwellRaw / 500.0 - dwell) > 1e-6) {
                    report.warn("站台 \"" + out.get("name") + "\" dwellTime=" + dwellRaw
                            + " 不是 500 的整数倍，已四舍五入为 " + dwell);
                }
                out.put("dwell_time", dwell);
                out.put("adc_time", 0);
                out.put("stop_without_opening_doors", false);
                out.put("psd_display_mode", 0);

                Path written = writeObject(outDim.resolve("platforms"), id, out);
                report.count("platforms", Report.Status.OK);
                report.detail("[OK]   platforms " + rel + "  \"" + out.get("name") + "\" dwell "
                        + (long) dwellRaw + "→" + dwell + " → "
                        + outDim.resolve("platforms").relativize(written).toString().replace('\\', '/'));
            } catch (Exception e) {
                report.count("platforms", Report.Status.FAIL);
                report.detail("[失败] platforms " + rel + " : " + e);
            }
        }
    }

    private static void convertRoutes(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long id = Util.getLong(src, "id", 0);
                List<Object> routePlatforms = Util.list(src, "routePlatformData");
                List<Object> platformIds = new ArrayList<>();
                List<Object> destinations = new ArrayList<>();
                List<Object> stopWithout = new ArrayList<>();
                List<Object> customDwell = new ArrayList<>();
                List<Object> dwellTimes = new ArrayList<>();
                List<Object> customAdc = new ArrayList<>();
                List<Object> adcTimes = new ArrayList<>();
                for (Object o : routePlatforms) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> rp = MsgPack.cast(o);
                    platformIds.add(Util.getLong(rp, "platformId", Util.getLong(rp, "platform_id", 0)));
                    destinations.add(Util.getString(rp, "customDestination", ""));
                    stopWithout.add(Util.getBool(rp, "stopWithoutOpeningDoors", false));
                    customDwell.add(Util.getBool(rp, "customDwellTime", false));
                    dwellTimes.add(Util.getLong(rp, "dwellTime", 0));
                    customAdc.add(Util.getBool(rp, "customAdcTime", false));
                    adcTimes.add(Util.getLong(rp, "adcTime", 0));
                }
                String routeNumber = "";
                Object rawNumber = src.get("routeNumber");
                if (rawNumber instanceof Number) routeNumber = String.valueOf(((Number) rawNumber).longValue());
                else if (rawNumber instanceof String) routeNumber = (String) rawNumber;

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("transport_mode", Util.getString(src, "transportMode", "TRAIN"));
                out.put("name", Util.getString(src, "name", ""));
                out.put("color", Util.getLong(src, "color", 0));
                out.put("platform_ids", platformIds);
                out.put("custom_destinations", destinations);
                out.put("stop_without_opening_doors", stopWithout);
                out.put("custom_dwell_times", customDwell);
                out.put("platform_dwell_times", dwellTimes);
                out.put("custom_adc_times", customAdc);
                out.put("platform_adc_times", adcTimes);
                out.put("route_type", Util.getString(src, "routeType", "NORMAL"));
                out.put("is_light_rail_route", !routeNumber.isEmpty());
                out.put("is_route_hidden", Util.getBool(src, "hidden", false));
                out.put("disable_next_station_announcements", false);
                out.put("light_rail_route_number", routeNumber);
                out.put("circular_state", Util.getString(src, "circularState", "NONE"));

                if (!routeNumber.isEmpty()) {
                    report.loss("线路 \"" + out.get("name") + "\"：routeNumber=\"" + routeNumber
                            + "\" 反推为 is_light_rail_route=true（原布尔值不可恢复）");
                }
                report.loss("线路 \"" + out.get("name") + "\"：disable_next_station_announcements 置 false"
                        + "（MTR4 把它存在世界级 data/mtr.dat 里，MVP 未解析）");

                Path written = writeObject(outDim.resolve("routes"), id, out);
                report.count("routes", Report.Status.OK);
                report.detail("[OK]   routes " + rel + "  \"" + out.get("name") + "\" 站台数 "
                        + platformIds.size() + " → " + outDim.resolve("routes").relativize(written).toString().replace('\\', '/'));
            } catch (Exception e) {
                report.count("routes", Report.Status.FAIL);
                report.detail("[失败] routes " + rel + " : " + e);
            }
        }
    }

    private static void convertDepots(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long id = Util.getLong(src, "id", 0);
                long[] p1 = Util.position(src, "position1");
                long[] p2 = Util.position(src, "position2");
                List<Object> routeIds = new ArrayList<>(Util.list(src, "routeIds"));
                List<Object> freq = new ArrayList<>();
                for (Object o : Util.list(src, "frequencies")) {
                    if (freq.size() >= 24) break;
                    freq.add(((Number) o).longValue());
                }
                while (freq.size() < 24) freq.add(10L);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("transport_mode", Util.getString(src, "transportMode", "TRAIN"));
                out.put("name", Util.getString(src, "name", ""));
                out.put("color", Util.getLong(src, "color", 0));
                out.put("x_min", Math.min(p1[0], p2[0]));
                out.put("z_min", Math.min(p1[2], p2[2]));
                out.put("x_max", Math.max(p1[0], p2[0]));
                out.put("z_max", Math.max(p1[2], p2[2]));
                out.put("route_ids", routeIds);
                out.put("use_real_time", Util.getBool(src, "useRealTime", false));
                out.put("repeat_infinitely", Util.getBool(src, "repeatInfinitely", true));
                out.put("cruising_altitude", Util.getInt(src, "cruisingAltitude", 256));
                out.put("frequencies", freq);
                out.put("departures", new ArrayList<>());
                out.put("deploy_index", 0);

                report.loss("车厂 \"" + out.get("name") + "\"：lastGenerated* 生成状态丢弃（MTR3 会重新生成）");
                Path written = writeObject(outDim.resolve("depots"), id, out);
                report.count("depots", Report.Status.OK);
                report.detail("[OK]   depots " + rel + "  \"" + out.get("name") + "\" 线路数 "
                        + routeIds.size() + " → " + outDim.resolve("depots").relativize(written).toString().replace('\\', '/'));
            } catch (Exception e) {
                report.count("depots", Report.Status.FAIL);
                report.detail("[失败] depots " + rel + " : " + e);
            }
        }
    }

    private static void convertSidings(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long id = Util.getLong(src, "id", 0);
                long[] p1 = Util.position(src, "position1");
                long[] p2 = Util.position(src, "position2");
                double railLength = Util.getDouble(src, "railLength", 0);
                int maxVehicles = Util.getInt(src, "maxVehicles", 0);
                double acceleration = Util.getDouble(src, "acceleration", 0);
                double accelConstant = acceleration * 2500.0;
                if (accelConstant < 1e-4) accelConstant = 0.01;
                List<Object> cars = Util.list(src, "vehicleCars");

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", id);
                out.put("transport_mode", Util.getString(src, "transportMode", "TRAIN"));
                out.put("name", Util.getString(src, "name", ""));
                out.put("color", Util.getLong(src, "color", 0));
                out.put("pos_1", Util.pack(p1[0], p1[1], p1[2]));
                out.put("pos_2", Util.pack(p2[0], p2[1], p2[2]));
                out.put("dwell_time", 20);
                out.put("adc_time", 0);
                out.put("stop_without_opening_doors", false);
                out.put("psd_display_mode", 0);
                out.put("rail_length", (float) Util.round(railLength, 3));
                out.put("train_custom_id", "train_24_2");
                out.put("train_type", "train_24_2");
                out.put("unlimited_trains", maxVehicles <= 0);
                out.put("max_trains", Math.max(0, maxVehicles));
                out.put("is_manual", false);
                out.put("max_manual_speed", 0);
                out.put("enable_predictive_braking", false);
                out.put("repeat_index_1", 0);
                out.put("repeat_index_2", 0);
                out.put("acceleration_constant", (float) accelConstant);
                out.put("path", new ArrayList<>());
                out.put("trains", new ArrayList<>());

                if (!cars.isEmpty()) {
                    report.loss("侧线 \"" + out.get("name") + "\"：vehicleCars[" + cars.size()
                            + "] 显式车厢几何无法反查车型，已用默认 train_type=train_24_2（SP1900）");
                }
                report.loss("侧线 \"" + out.get("name") + "\"：path 置空，由 MTR3 在车厂发车时重新计算");

                Path written = writeObject(outDim.resolve("sidings"), id, out);
                report.count("sidings", Report.Status.OK);
                report.detail("[OK]   sidings " + rel + "  \"" + out.get("name") + "\" rail_length="
                        + Util.round(railLength, 3) + " accel=" + accelConstant + " → "
                        + outDim.resolve("sidings").relativize(written).toString().replace('\\', '/'));
            } catch (Exception e) {
                report.count("sidings", Report.Status.FAIL);
                report.detail("[失败] sidings " + rel + " : " + e);
            }
        }
    }

    /* ================================================================ rails */

    private static void convertRails(Path dir, Path outDim, Report report, Progress progress) throws IOException {
        Map<Long, Map<Long, Map<String, Object>>> nodes = new LinkedHashMap<>();
        int segmentOk = 0;
        boolean stylesReported = false;
        boolean speedReported = false;
        boolean signalReported = false;

        for (String file : Versions.filesUnder(dir)) {
            Path p = Paths.get(file);
            String rel = dir.relativize(p).toString().replace('\\', '/');
            try {
                Map<String, Object> src = MsgPack.readObject(Files.readAllBytes(p));
                long[] p1 = Util.position(src, "position1");
                long[] p2 = Util.position(src, "position2");
                int a1 = RailGeom.angleIndex(Util.getString(src, "angle1", ""));
                int a2 = RailGeom.angleIndex(Util.getString(src, "angle2", ""));
                if (a1 < 0 || a2 < 0) {
                    report.count("rails", Report.Status.SKIP);
                    report.detail("[跳过] rails " + rel + " : 角度无法识别 (angle1=" + src.get("angle1")
                            + ", angle2=" + src.get("angle2") + ")");
                    continue;
                }
                String shape = Util.getString(src, "shape", "QUADRATIC");
                RailGeom.Params fwd = RailGeom.compute(p1[0], (int) p1[1], p1[2], a1, p2[0], (int) p2[1], p2[2], a2);
                if (!RailGeom.endpointCheck(fwd, p1[0], p1[2], p2[0], p2[2], 0.75)) {
                    report.count("rails", Report.Status.SKIP);
                    report.detail("[跳过] rails " + rel + " : 几何自检失败（端点反算对不上），原文件保留在备份里");
                    report.warn("轨道 " + rel + " 几何无法反算，已跳过（MTR3 里会缺这一段）");
                    continue;
                }
                int ra1 = a2;
                int ra2 = a1;
                RailGeom.Params rev = RailGeom.compute(p2[0], (int) p2[1], p2[2], ra1, p1[0], (int) p1[1], p1[2], ra2);
                boolean reverseOk = RailGeom.endpointCheck(rev, p2[0], p2[2], p1[0], p1[2], 0.75);
                if (!reverseOk) {
                    // some segments only invert when the facings are flipped as well
                    RailGeom.Params alt = RailGeom.compute(p2[0], (int) p2[1], p2[2], RailGeom.opposite(a2),
                            p1[0], (int) p1[1], p1[2], RailGeom.opposite(a1));
                    if (RailGeom.endpointCheck(alt, p2[0], p2[2], p1[0], p1[2], 0.75)) {
                        rev = alt;
                        reverseOk = true;
                    }
                }

                boolean isPlatform = Util.getBool(src, "isPlatform", false);
                boolean isSiding = Util.getBool(src, "isSiding", false);
                boolean canTurnBack = Util.getBool(src, "canTurnBack", false);
                double speed1 = Util.getDouble(src, "speedLimit1", 0);
                double speed2 = Util.getDouble(src, "speedLimit2", 0);
                double verticalRadius = Util.getDouble(src, "verticalRadius", 0);
                String railType = pickRailType(shape, isPlatform, isSiding, canTurnBack, speed1, speed2);
                String mode = Util.getString(src, "transportMode", "TRAIN");

                long node1 = Util.pack(p1[0], p1[1], p1[2]);
                long node2 = Util.pack(p2[0], p2[1], p2[2]);

                Map<Long, Map<String, Object>> conn1 = nodes.computeIfAbsent(node1, k -> new LinkedHashMap<>());
                Map<Long, Map<String, Object>> conn2 = nodes.computeIfAbsent(node2, k -> new LinkedHashMap<>());
                if (conn1.containsKey(node2)) {
                    report.count("rails", Report.Status.SKIP);
                    report.detail("[跳过] rails " + rel + " : 重复轨道（同端点已存在）");
                    continue;
                }
                conn1.put(node2, railEntry(node2, fwd, railType, mode, verticalRadius));
                if (reverseOk) {
                    conn2.put(node1, railEntry(node1, rev, railType, mode, verticalRadius));
                } else {
                    report.warn("轨道 " + rel + " 只能单向（反向几何无法反算），MTR3 里这条轨道仅支持单方向通行");
                }
                segmentOk++;
                report.count("rails", Report.Status.OK);
                report.detail("[OK]   rails " + rel + "  " + shape + " " + railType
                        + " [" + p1[0] + "," + p1[1] + "," + p1[2] + "]→[" + p2[0] + "," + p2[1] + "," + p2[2]
                        + "] 长度 " + Util.round(fwd.getLength(), 2));

                if (!stylesReported && !Util.list(src, "styles").isEmpty()) {
                    List<Object> styles = Util.list(src, "styles");
                    if (!(styles.size() == 1 && "default".equals(String.valueOf(styles.get(0))))) {
                        report.loss("轨道 styles[]（每段模型/装饰）：MTR3 无 per-segment 模型概念，已丢弃 "
                                + styles);
                    }
                    stylesReported = true;
                }
                if (!speedReported && (speed1 > 0 || speed2 > 0)) {
                    report.loss("轨道 speedLimit1/2（每段限速）：MTR3 限速由 rail_type 决定，已按最近档位映射");
                    speedReported = true;
                }
                if (!signalReported && !Util.list(src, "signalColors").isEmpty()) {
                    report.loss("轨道 signalColors[]（挂在轨道上的信号色）：MTR3 用独立 signal-blocks 对象，MVP 未重建");
                    signalReported = true;
                }
            } catch (Exception e) {
                report.count("rails", Report.Status.FAIL);
                report.detail("[失败] rails " + rel + " : " + e);
            }
        }

        int written = 0;
        for (Map.Entry<Long, Map<Long, Map<String, Object>>> node : nodes.entrySet()) {
            if (node.getValue().isEmpty()) continue;
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("node_pos", node.getKey());
            root.put("rail_connections", new ArrayList<>(node.getValue().values()));
            writeObject(outDim.resolve("rails"), node.getKey(), root);
            written++;
        }
        if (segmentOk > 0) progress.log("轨道: " + segmentOk + " 段 → " + written + " 个节点文件");
    }

    private static Map<String, Object> railEntry(long otherNode, RailGeom.Params g, String railType,
                                                 String transportMode, double verticalRadius) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("node_pos", otherNode);
        c.put("h_1", g.h1);
        c.put("k_1", g.k1);
        c.put("h_2", g.h2);
        c.put("k_2", g.k2);
        c.put("r_1", g.r1);
        c.put("r_2", g.r2);
        c.put("t_start_1", g.tStart1);
        c.put("t_end_1", g.tEnd1);
        c.put("t_start_2", g.tStart2);
        c.put("t_end_2", g.tEnd2);
        c.put("y_start", (long) g.yStart);
        c.put("y_end", (long) g.yEnd);
        c.put("reverse_t_1", g.reverseT1);
        c.put("is_straight_1", g.isStraight1);
        c.put("reverse_t_2", g.reverseT2);
        c.put("is_straight_2", g.isStraight2);
        c.put("rail_type", railType);
        c.put("transport_mode", transportMode);
        c.put("rail_data", "\u0000\u0000\u0000\u0000");
        c.put("model_key", "");
        c.put("is_secondary_dir", false);
        c.put("vertical_curve_radius", verticalRadius > 0 ? (float) verticalRadius : 0L);
        c.put("custom_configs", "\u0000\u0000\u0000\u0000");
        c.put("roll_angle_map", "");
        c.put("opening_direction", 0L);
        c.put("rolling_offset", 0.7174999713897705f);
        return c;
    }

    private static final int[] RAIL_SPEEDS = {20, 40, 60, 80, 120, 160, 200, 300};
    private static final String[] RAIL_NAMES = {
            "WOODEN", "STONE", "EMERALD", "IRON", "OBSIDIAN", "BLAZE", "QUARTZ", "DIAMOND"
    };

    static String pickRailType(String shape, boolean isPlatform, boolean isSiding, boolean canTurnBack,
                               double speed1, double speed2) {
        if ("CABLE".equalsIgnoreCase(shape)) return "CABLE_CAR";
        if (isSiding) return "SIDING";
        if (isPlatform) return "PLATFORM";
        if (canTurnBack) return "TURN_BACK";
        double speed = Math.max(speed1, speed2);
        if (speed <= 0) return "IRON";
        int best = 3;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < RAIL_SPEEDS.length; i++) {
            double diff = Math.abs(RAIL_SPEEDS[i] - speed);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return RAIL_NAMES[best];
    }

    /* ================================================================ verify */

    private static final Map<String, String[]> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("stations", new String[]{"id", "transport_mode", "name", "color", "x_min", "z_min", "x_max", "z_max", "zone", "exits"});
        REQUIRED.put("platforms", new String[]{"id", "transport_mode", "name", "color", "pos_1", "pos_2", "dwell_time"});
        REQUIRED.put("routes", new String[]{"id", "platform_ids", "custom_destinations", "route_type", "circular_state"});
        REQUIRED.put("depots", new String[]{"id", "x_min", "z_min", "x_max", "z_max", "route_ids", "frequencies"});
        REQUIRED.put("sidings", new String[]{"id", "pos_1", "pos_2", "rail_length", "path"});
    }

    /** Re-reads every generated file and compares it with the schema MTR3 itself writes. */
    private static void verifyTree(Path output, Path outMtr, Report report) {
        int checked = 0;
        int exact = 0;
        List<String> broken = new ArrayList<>();
        java.util.Map<String, Integer> perKind = new LinkedHashMap<>();
        try (var stream = Files.walk(outMtr)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String path = file.toString().replace('\\', '/');
                String kind = null;
                for (String k : Schema.kinds()) {
                    if (path.contains("/" + k + "/")) kind = k;
                }
                if (kind == null) continue;
                checked++;
                try {
                    Map<String, Object> obj = MsgPack.readObject(Files.readAllBytes(file));
                    List<String> problems = Schema.check(kind, obj);
                    if (problems.isEmpty()) {
                        exact++;
                    } else {
                        for (String p : problems) {
                            if (broken.size() < 200) broken.add(path + " → " + p);
                        }
                    }
                    perKind.merge(kind, 1, Integer::sum);
                } catch (Exception e) {
                    broken.add(path + " (无法解码: " + e + ")");
                }
            }
        } catch (IOException ignored) {
            // ignore
        }

        report.detail("[校验] " + checked + " 个对象文件：键集合/类型与 MTR3 原生文件完全一致 " + exact + " 个，"
                + "不一致 " + (checked - exact) + " 个");
        report.detail("[校验] 分类型数量: " + perKind);
        if (!broken.isEmpty()) {
            for (String b : broken) report.warn("校验差异: " + b);
        }

        // persistent state files: present, correct shape, and no inline snapshot
        int states = 0;
        try (var stream = Files.walk(output)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (!file.getFileName().toString().equals("mtr_train_data.dat")) continue;
                states++;
                String rel = output.relativize(file).toString().replace('\\', '/');
                List<String> keys = Nbt.rootKeys(file);
                boolean hasData = keys.contains("data:" + Nbt.TAG_COMPOUND);
                boolean hasVersion = keys.stream().anyMatch(k -> k.startsWith("DataVersion:"));
                if (!hasData || !hasVersion) {
                    report.warn("校验差异: " + rel + " 缺少 data/DataVersion（根键 " + keys + "）");
                } else if (Nbt.hasRawMessagePack(file)) {
                    report.warn("校验差异: " + rel + " 含 raw_message_pack，MTR3 会忽略 mtr/ 文件树");
                } else {
                    report.detail("[校验] " + rel + " 存在且不含 raw_message_pack（MTR3 将读取 mtr/ 文件树）");
                }
            }
        } catch (IOException ignored) {
            // ignore
        }
        if (states == 0) {
            report.warn("校验差异: 输出里没有任何 data/mtr_train_data.dat —— MTR3 会把它当成空存档");
        }
    }

    /* ================================================================ write */

    private static Path writeObject(Path kindDir, long id, Map<String, Object> value) throws IOException {
        Path file = kindDir.resolve(Util.mtr3Bucket(id)).resolve(Long.toString(id));
        guard(file);
        Files.createDirectories(file.getParent());
        MsgPack.Writer writer = new MsgPack.Writer();
        writer.pack(value);
        Files.write(file, writer.toByteArray());
        return file;
    }

    private static List<Path> listDirs(Path dir) {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // ignore
        }
        return out;
    }

    private static List<Object> toList(Object o) {
        List<Object> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) out.add(item instanceof String ? item : String.valueOf(item));
        }
        return out;
    }

    /** UTF-8 holder to avoid repeating StandardCharsets imports in lambdas. */
    private static final class StandardCharsetsHolder {
        static final java.nio.charset.Charset UTF8 = java.nio.charset.StandardCharsets.UTF_8;
    }
}
