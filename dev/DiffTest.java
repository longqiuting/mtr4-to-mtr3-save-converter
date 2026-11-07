import mtrconv.MsgPack;
import mtrconv.RailGeom;
import mtrconv.Util;

import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/**
 * Dev-only differential test: MTR4's real RailMath (loaded out of the shipped jar)
 * vs the ported {@link RailGeom}. Not part of the shipped tool.
 *
 * <p>Usage: {@code DiffTest <mtr-jar> save <saveDir>} or {@code DiffTest <mtr-jar> random [n]}</p>
 */
public class DiffTest {

    private static Method[] numeric;
    private static Method[] boolM;
    private static Constructor<?> posK, mathK;
    private static Object[] angles;
    private static Object shape;
    private static final String[] NAMES = {"E", "SEE", "SE", "SSE", "S", "SSW", "SW", "SWW", "W", "NWW", "NW", "NNW", "N", "NNE", "NE", "NEE"};

    public static void main(String[] args) throws Exception {
        setup(args[0]);
        if ("save".equals(args[1])) compareSave(Paths.get(args[2]));
        else randomCases(args.length > 2 ? Integer.parseInt(args[2]) : 20000);
    }

    private static void setup(String jar) throws Exception {
        URLClassLoader cl = new URLClassLoader(new URL[]{new java.io.File(jar).toURI().toURL()}, null);
        Class<?> posC = Class.forName("org.mtr.core.data.Position", true, cl);
        Class<?> angleC = Class.forName("org.mtr.core.tool.Angle", true, cl);
        Class<?> shapeC = Class.forName("org.mtr.core.data.Rail$Shape", true, cl);
        Class<?> mathC = Class.forName("org.mtr.core.data.RailMath", true, cl);
        posK = posC.getConstructor(long.class, long.class, long.class);
        mathK = mathC.getConstructor(posC, angleC, posC, angleC, shapeC, double.class);
        angles = new Object[16];
        for (int i = 0; i < 16; i++) angles[i] = Enum.valueOf((Class) angleC, NAMES[i]);
        shape = Enum.valueOf((Class) shapeC, "QUADRATIC");
        String[] fields = {"h1", "k1", "h2", "k2", "r1", "r2", "tStart1", "tEnd1", "tStart2", "tEnd2"};
        numeric = new Method[fields.length];
        for (int i = 0; i < fields.length; i++) {
            Field f = mathC.getDeclaredField(fields[i]);
            f.setAccessible(true);
            final Field ff = f;
            numeric[i] = null;
            fieldsF[i] = ff;
        }
        Field[] bf = new Field[4];
        String[] bnames = {"isStraight1", "isStraight2", "reverseT1", "reverseT2"};
        for (int i = 0; i < 4; i++) {
            bf[i] = mathC.getDeclaredField(bnames[i]);
            bf[i].setAccessible(true);
        }
        fieldsB = bf;
    }

    private static Field[] fieldsF = new Field[10];
    private static Field[] fieldsB;

    private static double mine(int i, RailGeom.Params p) {
        return switch (i) {
            case 0 -> p.h1;
            case 1 -> p.k1;
            case 2 -> p.h2;
            case 3 -> p.k2;
            case 4 -> p.r1;
            case 5 -> p.r2;
            case 6 -> p.tStart1;
            case 7 -> p.tEnd1;
            case 8 -> p.tStart2;
            default -> p.tEnd2;
        };
    }

    private static void compareSave(Path save) throws Exception {
        Path mtr = Files.isDirectory(save.resolve("mtr")) ? save.resolve("mtr") : save;
        int total = 0, bad = 0, badStruct = 0;
        double worst = 0;
        String worstCase = "";
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(mtr)) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().replace('\\', '/').contains("/rails/")).forEach(files::add);
        }
        for (Path file : files) {
            Map<String, Object> root = MsgPack.readObject(Files.readAllBytes(file));
            Object raw = root.get("rail_connections");
            List<?> conns = raw instanceof List ? (List<?>) raw : null;
            if (conns != null) {
                for (Object o : conns) {
                    Map<String, Object> c = MsgPack.cast(o);
                    if (!c.containsKey("pos_start") || !c.containsKey("facing_start")) continue;
                    long[] ps = Util.unpack(Util.getLong(c, "pos_start", 0));
                    long[] pe = Util.unpack(Util.getLong(c, "pos_end", 0));
                    int a1 = RailGeom.fromRadians(Util.getDouble(c, "facing_start", 0));
                    int a2 = RailGeom.fromRadians(Util.getDouble(c, "facing_end", 0));
                    total++;
                    double[] diff = compare(ps[0], ps[1], ps[2], a1, pe[0], pe[1], pe[2], a2);
                    if (diff[0] > 1e-3) bad++;
                    if (diff[1] != 0) badStruct++;
                    if (diff[0] > worst) {
                        worst = diff[0];
                        worstCase = file.getFileName() + " " + ps[0] + "," + ps[2] + " " + NAMES[a1] + " -> "
                                + pe[0] + "," + pe[2] + " " + NAMES[a2] + " diff=" + diff[0] + " struct=" + diff[1];
                    }
                }
            } else if (root.containsKey("position1")) {
                // MTR4 rail file
                long[] ps = Util.position(root, "position1");
                long[] pe = Util.position(root, "position2");
                int a1 = RailGeom.angleIndex(Util.getString(root, "angle1", ""));
                int a2 = RailGeom.angleIndex(Util.getString(root, "angle2", ""));
                if (a1 < 0 || a2 < 0) continue;
                total++;
                double[] diff = compare(ps[0], ps[1], ps[2], a1, pe[0], pe[1], pe[2], a2);
                if (diff[0] > 1e-3) bad++;
                if (diff[1] != 0) badStruct++;
                if (diff[0] > worst) {
                    worst = diff[0];
                    worstCase = file.getFileName() + " " + ps[0] + "," + ps[2] + " " + NAMES[a1] + " -> "
                            + pe[0] + "," + pe[2] + " " + NAMES[a2] + " diff=" + diff[0] + " struct=" + diff[1];
                }
            }
        }
        System.out.println("rails compared   : " + total);
        System.out.println("numeric mismatch : " + bad + " (tol 1e-3)");
        System.out.println("structural differ: " + badStruct);
        System.out.println("worst            : " + worst + (worstCase.isEmpty() ? "" : "  @ " + worstCase));
    }

    private static double[] compare(long x1, long y1, long z1, int a1, long x2, long y2, long z2, int a2) throws Exception {
        Object real = mathK.newInstance(posK.newInstance(x1, y1, z1), angles[a1],
                posK.newInstance(x2, y2, z2), angles[a2], shape, 0.0);
        RailGeom.Params mine = RailGeom.compute(x1, (int) y1, z1, a1, x2, (int) y2, z2, a2);
        double worst = 0;
        for (int i = 0; i < 10; i++) worst = Math.max(worst, Math.abs(fieldsF[i].getDouble(real) - mine(i, mine)));
        boolean structOk = fieldsB[0].getBoolean(real) == mine.isStraight1
                && fieldsB[1].getBoolean(real) == mine.isStraight2
                && fieldsB[2].getBoolean(real) == mine.reverseT1
                && fieldsB[3].getBoolean(real) == mine.reverseT2;
        return new double[]{worst, structOk ? 0 : 1};
    }

    private static void randomCases(int n) throws Exception {
        Random random = new Random(20261106L);
        int bad = 0, badStruct = 0;
        double worst = 0;
        String worstCase = "";
        for (int i = 0; i < n; i++) {
            long x1 = random.nextInt(600) - 300, z1 = random.nextInt(600) - 300;
            long y1 = random.nextInt(80) - 20, y2 = random.nextInt(80) - 20;
            int a1 = random.nextInt(16);
            int a2 = random.nextBoolean() ? (a1 + (random.nextBoolean() ? 0 : 8)) % 16 : random.nextInt(16);
            long x2, z2;
            if (random.nextInt(3) == 0) {
                int len = 1 + random.nextInt(60);
                double rad = Math.toRadians(deg(a1));
                x2 = x1 + Math.round(len * Math.cos(rad));
                z2 = z1 + Math.round(len * Math.sin(rad));
            } else {
                x2 = x1 + random.nextInt(33) - 16;
                z2 = z1 + random.nextInt(33) - 16;
            }
            double[] diff = compare(x1, y1, z1, a1, x2, y2, z2, a2);
            if (diff[0] > 1e-3) bad++;
            if (diff[1] != 0) badStruct++;
            if (diff[0] > worst) {
                worst = diff[0];
                worstCase = "(" + x1 + "," + z1 + ") " + NAMES[a1] + " -> (" + x2 + "," + z2 + ") " + NAMES[a2];
            }
        }
        System.out.println("cases            : " + n);
        System.out.println("numeric mismatch : " + bad);
        System.out.println("structural differ: " + badStruct);
        System.out.println("worst            : " + worst + "  @ " + worstCase);
    }

    private static double deg(int i) {
        double d = i * 22.5;
        return d >= 180 ? d - 360 : d;
    }
}
