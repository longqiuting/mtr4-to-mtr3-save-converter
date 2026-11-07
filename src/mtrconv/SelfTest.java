package mtrconv;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Development self-test: replays real MTR3 rail files whose connections carry
 * {@code pos_start / facing_start / pos_end / facing_end} (written by YMTR 3.6.4.x)
 * through {@link RailGeom} and compares every derived parameter with what MTR3
 * itself stored. This validates the ported geometry without opening the game.
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static void main(String[] args) throws IOException {
        Path save = Paths.get(args[0]);
        Path mtr = Files.isDirectory(save.resolve("mtr")) ? save.resolve("mtr") : save;
        int files = 0, conns = 0, withPath = 0, mismatched = 0, reverseChecked = 0, reverseMismatched = 0;
        List<String> failures = new ArrayList<>();

        Map<String, RailGeom.Params> byEdge = new HashMap<>();
        Map<String, int[]> edgeAngles = new HashMap<>();

        try (Stream<Path> stream = Files.walk(mtr)) {
            for (Path f : (Iterable<Path>) stream.filter(p -> p.toString().replace('\\', '/').contains("/rails/"))::iterator) {
                if (!Files.isRegularFile(f)) continue;
                files++;
                Map<String, Object> root = MsgPack.readObject(Files.readAllBytes(f));
                long nodePos = Util.getLong(root, "node_pos", 0);
                Object raw = root.get("rail_connections");
                if (!(raw instanceof List)) continue;
                for (Object o : (List<?>) raw) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> c = MsgPack.cast(o);
                    conns++;
                    long endPos = Util.getLong(c, "node_pos", 0);
                    boolean hasPath = c.containsKey("pos_start") && c.containsKey("facing_start")
                            && c.containsKey("pos_end") && c.containsKey("facing_end");
                    if (hasPath) {
                        withPath++;
                        long[] ps = Util.unpack(Util.getLong(c, "pos_start", nodePos));
                        long[] pe = Util.unpack(Util.getLong(c, "pos_end", 0));
                        int a1 = RailGeom.fromRadians(Util.getDouble(c, "facing_start", 0));
                        int a2 = RailGeom.fromRadians(Util.getDouble(c, "facing_end", 0));
                        RailGeom.Params p = RailGeom.compute(ps[0], (int) ps[1], ps[2], a1, pe[0], (int) pe[1], pe[2], a2);
                        String bad = compare(c, p);
                        if (bad != null) {
                            mismatched++;
                            if (failures.size() < 10) failures.add(f.getFileName() + " -> " + endPos + " : " + bad);
                        }
                        byEdge.put(nodePos + ">" + endPos, p);
                        edgeAngles.put(nodePos + ">" + endPos, new int[]{a1, a2});
                    }
                }
            }
        }

        for (Map.Entry<String, RailGeom.Params> e : byEdge.entrySet()) {
            String[] parts = e.getKey().split(">");
            String reverseKey = parts[1] + ">" + parts[0];
            RailGeom.Params reverse = byEdge.get(reverseKey);
            if (reverse == null) continue;
            int[] a = edgeAngles.get(e.getKey());
            long[] ps = Util.unpack(Long.parseLong(parts[0]));
            long[] pe = Util.unpack(Long.parseLong(parts[1]));
            // MTR3 stores the mirrored entry as "swap endpoints, keep the same facing
            // values" — verified against 1,706 real pairs (see README §3).
            RailGeom.Params mirrored = RailGeom.compute(pe[0], (int) pe[1], pe[2], a[1],
                    ps[0], (int) ps[1], ps[2], a[0]);
            reverseChecked++;
            double err = maxDiff(reverse, mirrored);
            if (err > 1e-3) {
                reverseMismatched++;
                if (failures.size() < 20) failures.add("MIRROR " + reverseKey + " maxDiff=" + err);
            }
        }

        System.out.println("rail files        : " + files);
        System.out.println("connections       : " + conns);
        System.out.println("with pos/facing   : " + withPath);
        System.out.println("forward mismatch  : " + mismatched);
        System.out.println("mirror pairs      : " + reverseChecked + "  mismatch: " + reverseMismatched);
        for (String s : failures) System.out.println("  ! " + s);
        System.out.println();
        System.out.println("说明：这里比对的是「存档里已经写好的参数」和「本工具按同一公式算出的参数」。");
        System.out.println("      mirror 一项（反向记录约定）应当 0 不一致 —— 它说明写出的双向轨道和 MTR3 自己的写法一致。");
        System.out.println("      forward 若有个别不一致，通常是因为该存档由别的 MTR 构建写过（float 精度 / 旧版本算法），");
        System.out.println("      属于存档既有数据，不是转换路径的数据源；权威校验请用 dev/DiffTest 对 MTR4 的 RailMath 做差分。");
    }

    private static double maxDiff(RailGeom.Params a, RailGeom.Params b) {
        double m = 0;
        m = Math.max(m, Math.abs(a.h1 - b.h1));
        m = Math.max(m, Math.abs(a.k1 - b.k1));
        m = Math.max(m, Math.abs(a.h2 - b.h2));
        m = Math.max(m, Math.abs(a.k2 - b.k2));
        m = Math.max(m, Math.abs(a.r1 - b.r1));
        m = Math.max(m, Math.abs(a.r2 - b.r2));
        m = Math.max(m, Math.abs(a.tStart1 - b.tStart1));
        m = Math.max(m, Math.abs(a.tEnd1 - b.tEnd1));
        m = Math.max(m, Math.abs(a.tStart2 - b.tStart2));
        m = Math.max(m, Math.abs(a.tEnd2 - b.tEnd2));
        return m;
    }

    private static String compare(Map<String, Object> c, RailGeom.Params p) {
        StringBuilder sb = new StringBuilder();
        check(sb, "h_1", Util.getDouble(c, "h_1", 0), p.h1);
        check(sb, "k_1", Util.getDouble(c, "k_1", 0), p.k1);
        check(sb, "h_2", Util.getDouble(c, "h_2", 0), p.h2);
        check(sb, "k_2", Util.getDouble(c, "k_2", 0), p.k2);
        check(sb, "r_1", Util.getDouble(c, "r_1", 0), p.r1);
        check(sb, "r_2", Util.getDouble(c, "r_2", 0), p.r2);
        check(sb, "t_start_1", Util.getDouble(c, "t_start_1", 0), p.tStart1);
        check(sb, "t_end_1", Util.getDouble(c, "t_end_1", 0), p.tEnd1);
        check(sb, "t_start_2", Util.getDouble(c, "t_start_2", 0), p.tStart2);
        check(sb, "t_end_2", Util.getDouble(c, "t_end_2", 0), p.tEnd2);
        if (Util.getBool(c, "is_straight_1", false) != p.isStraight1) sb.append("is_straight_1;");
        if (Util.getBool(c, "is_straight_2", false) != p.isStraight2) sb.append("is_straight_2;");
        if (Util.getBool(c, "reverse_t_1", false) != p.reverseT1) sb.append("reverse_t_1;");
        if (Util.getBool(c, "reverse_t_2", false) != p.reverseT2) sb.append("reverse_t_2;");
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void check(StringBuilder sb, String name, double stored, double computed) {
        // Stored values come from a build that computed cos/sin in float precision,
        // so exact-equality is wrong here; 1e-3 on radii/arc lengths is ~float noise.
        if (Math.abs(stored - computed) > 1e-3) {
            sb.append(name).append(": stored=").append(stored).append(" computed=").append(computed).append("; ");
        }
    }
}
