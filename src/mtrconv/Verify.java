package mtrconv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only checker: compares an existing MTR3 save (or a converted output) against the
 * schema MTR3 3.6.4.2 writes, and checks the {@code data/mtr_train_data.dat} trigger files.
 *
 * <p>Nothing is written; safe to point at any save, including the mod's own.</p>
 */
public final class Verify {

    private Verify() {
    }

    public static final class KindStat {
        public int files;
        public int exact;
        public final List<String> problems = new ArrayList<>();
    }

    public static Map<String, KindStat> objects(Path save) throws IOException {
        Path mtr = save.resolve("mtr");
        Map<String, KindStat> stats = new LinkedHashMap<>();
        for (String k : Schema.kinds()) stats.put(k, new KindStat());
        if (!Files.isDirectory(mtr)) return stats;

        try (var stream = Files.walk(mtr)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Path rel = mtr.relativize(file);
                if (rel.getNameCount() < 3) continue;
                String kind = rel.getName(2).toString();
                KindStat st = stats.get(kind);
                if (st == null) continue;
                st.files++;
                try {
                    Map<String, Object> obj = MsgPack.readObject(Files.readAllBytes(file));
                    List<String> problems = Schema.check(kind, obj);
                    if (problems.isEmpty()) {
                        st.exact++;
                    } else {
                        for (String p : problems) {
                            if (st.problems.size() < 40) st.problems.add(rel.toString().replace('\\', '/') + " → " + p);
                        }
                    }
                } catch (Exception e) {
                    st.problems.add(rel.toString().replace('\\', '/') + " (无法解码: " + e + ")");
                }
            }
        }
        return stats;
    }

    /** Prints a report for {@code save}; returns true when everything matches. */
    public static boolean run(Path save) throws IOException {
        if (!Files.isDirectory(save.resolve("mtr"))) {
            System.err.println("不是 MTR 存档（没有 mtr/ 目录）: " + save);
            return false;
        }
        boolean ok = true;
        System.out.println("校验存档: " + save);
        System.out.println("--------------------------------------------------------------");
        for (Map.Entry<String, KindStat> e : objects(save).entrySet()) {
            KindStat st = e.getValue();
            if (st.files == 0) {
                System.out.printf("%-14s 无对象（0 个文件）%n", e.getKey());
                continue;
            }
            boolean good = st.problems.isEmpty();
            ok &= good;
            System.out.printf("%-14s 文件 %-4d 键集合/类型与 MTR3 原生一致 %-4d %s%n",
                    e.getKey(), st.files, st.exact, good ? "✓ missing=[] extra=[] typeDiff=[]" : "✗");
            for (String p : st.problems) System.out.println("    ! " + p);
        }

        System.out.println("--------------------------------------------------------------");
        int states = 0;
        try (var stream = Files.walk(save)) {
            for (Path f : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (!f.getFileName().toString().equals("mtr_train_data.dat")) continue;
                states++;
                List<String> keys = Nbt.rootKeys(f);
                boolean good = keys.contains("data:" + Nbt.TAG_COMPOUND) && !Nbt.hasRawMessagePack(f);
                ok &= good;
                System.out.printf("%-14s %s（根键 %s，raw_message_pack=%b）%s%n",
                        "mtr_train_data",
                        save.relativize(f).toString().replace('\\', '/'),
                        String.join(",", keys),
                        Nbt.hasRawMessagePack(f),
                        good ? "✓ MTR3 会读取 mtr/ 文件树" : "✗ MTR3 不会读取 mtr/ 文件树");
            }
        }
        if (states == 0) {
            ok = false;
            System.out.println("mtr_train_data  ✗ 缺少 data/mtr_train_data.dat —— MTR3 会把它当成空存档（轨道/车站/车厂全部消失）");
        }
        System.out.println("--------------------------------------------------------------");
        System.out.println(ok ? "结论: 通过" : "结论: 有问题，见上面 ✗ / ! 行");
        return ok;
    }
}
