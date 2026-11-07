import mtrconv.RailGeom;

import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;

/** Prints real RailMath params and the positions it reconstructs at both ends. */
public class OneCase {
    public static void main(String[] a) throws Exception {
        String jar = a[0];
        long x1 = Long.parseLong(a[1]), z1 = Long.parseLong(a[2]);
        int i1 = Integer.parseInt(a[3]);
        long x2 = Long.parseLong(a[4]), z2 = Long.parseLong(a[5]);
        int i2 = Integer.parseInt(a[6]);
        String[] N = {"E","SEE","SE","SSE","S","SSW","SW","SWW","W","NWW","NW","NNW","N","NNE","NE","NEE"};

        URLClassLoader cl = new URLClassLoader(new URL[]{new java.io.File(jar).toURI().toURL()}, null);
        Class<?> posC = Class.forName("org.mtr.core.data.Position", true, cl);
        Class<?> angleC = Class.forName("org.mtr.core.tool.Angle", true, cl);
        Class<?> shapeC = Class.forName("org.mtr.core.data.Rail$Shape", true, cl);
        Class<?> mathC = Class.forName("org.mtr.core.data.RailMath", true, cl);
        Object r = mathC.getConstructor(posC, angleC, posC, angleC, shapeC, double.class)
                .newInstance(posC.getConstructor(long.class, long.class, long.class).newInstance(x1, 0L, z1),
                        Enum.valueOf((Class) angleC, N[i1]),
                        posC.getConstructor(long.class, long.class, long.class).newInstance(x2, 0L, z2),
                        Enum.valueOf((Class) angleC, N[i2]),
                        Enum.valueOf((Class) shapeC, "QUADRATIC"), 0.0);
        RailGeom.Params m = RailGeom.compute(x1, 0, z1, i1, x2, 0, z2, i2);
        String[] f = {"h1","k1","h2","k2","r1","r2","tStart1","tEnd1","tStart2","tEnd2"};
        double[] mine = {m.h1, m.k1, m.h2, m.k2, m.r1, m.r2, m.tStart1, m.tEnd1, m.tStart2, m.tEnd2};
        for (int i = 0; i < f.length; i++) {
            Field fl = mathC.getDeclaredField(f[i]);
            fl.setAccessible(true);
            double rv = fl.getDouble(r);
            System.out.printf("%-9s real=%-22s mine=%-22s %s%n", f[i], rv, mine[i], Math.abs(rv - mine[i]) > 1e-6 ? " <-- DIFF" : "");
        }
        double len = (Double) mathC.getMethod("getLength").invoke(r);
        Method gp = mathC.getMethod("getPosition", double.class, boolean.class);
        Object p0 = gp.invoke(r, 0.0, false);
        Object p1 = gp.invoke(r, len, false);
        System.out.println("len=" + len);
        System.out.println("start real=" + p0 + "  expected=(" + (x1 + 0.5) + "," + (z1 + 0.5) + ")");
        System.out.println("end   real=" + p1 + "  expected=(" + (x2 + 0.5) + "," + (z2 + 0.5) + ")");
        System.out.println("endpointCheck(mine)=" + RailGeom.endpointCheck(m, x1, z1, x2, z2, 0.75));
    }
}
