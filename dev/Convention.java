import mtrconv.MsgPack;
import mtrconv.RailGeom;
import mtrconv.Util;

import java.nio.file.*;
import java.util.*;

/** Dev-only: which convention does MTR3 itself use for the mirrored rail entry? */
public class Convention {
    public static void main(String[] args) throws Exception {
        Path mtr = Paths.get(args[0]);
        if (Files.isDirectory(mtr.resolve("mtr"))) mtr = mtr.resolve("mtr");
        Map<String, Map<String, Object>> stored = new HashMap<>();
        Map<String, int[]> angles = new HashMap<>();
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(mtr)) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().replace('\\', '/').contains("/rails/")).forEach(files::add);
        }
        for (Path f : files) {
            Map<String, Object> root = MsgPack.readObject(Files.readAllBytes(f));
            Object raw = root.get("rail_connections");
            if (!(raw instanceof List)) continue;
            for (Object o : (List<?>) raw) {
                Map<String, Object> c = MsgPack.cast(o);
                if (!c.containsKey("pos_start")) continue;
                long a = Util.getLong(c, "pos_start", 0), b = Util.getLong(c, "pos_end", 0);
                stored.put(a + ">" + b, c);
                angles.put(a + ">" + b, new int[]{
                        RailGeom.fromRadians(Util.getDouble(c, "facing_start", 0)),
                        RailGeom.fromRadians(Util.getDouble(c, "facing_end", 0))});
            }
        }
        int pairs = 0, swapOk = 0, oppOk = 0, neither = 0;
        for (Map.Entry<String, Map<String, Object>> e : stored.entrySet()) {
            String[] p = e.getKey().split(">");
            String revKey = p[1] + ">" + p[0];
            Map<String, Object> rev = stored.get(revKey);
            if (rev == null) continue;
            pairs++;
            int[] an = angles.get(e.getKey());
            long[] a = Util.unpack(Long.parseLong(p[0]));
            long[] b = Util.unpack(Long.parseLong(p[1]));
            RailGeom.Params swap = RailGeom.compute(b[0], (int) b[1], b[2], an[1], a[0], (int) a[1], a[2], an[0]);
            RailGeom.Params opp = RailGeom.compute(b[0], (int) b[1], b[2], RailGeom.opposite(an[1]),
                    a[0], (int) a[1], a[2], RailGeom.opposite(an[0]));
            boolean s = matches(rev, swap), o = matches(rev, opp);
            if (s && o) swapOk++;
            else if (s) swapOk++;
            else if (o) oppOk++;
            else neither++;
        }
        System.out.println("pairs          : " + pairs);
        System.out.println("swap-convention: " + swapOk);
        System.out.println("opp-convention : " + oppOk);
        System.out.println("neither        : " + neither);
    }

    private static boolean matches(Map<String, Object> c, RailGeom.Params p) {
        return close(c, "h_1", p.h1) && close(c, "k_1", p.k1) && close(c, "h_2", p.h2) && close(c, "k_2", p.k2)
                && close(c, "r_1", p.r1) && close(c, "r_2", p.r2) && close(c, "t_start_1", p.tStart1)
                && close(c, "t_end_1", p.tEnd1) && close(c, "t_start_2", p.tStart2) && close(c, "t_end_2", p.tEnd2);
    }

    private static boolean close(Map<String, Object> c, String k, double v) {
        return Math.abs(Util.getDouble(c, k, 0) - v) < 1e-3;
    }
}
