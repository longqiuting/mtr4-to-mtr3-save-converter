import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;

/** Prints the exact intermediate values MTR's Angle/Vector produce for one case. */
public class Trace {
    public static void main(String[] a) throws Exception {
        URLClassLoader cl = new URLClassLoader(new URL[]{new java.io.File(a[0]).toURI().toURL()}, null);
        Class<?> angleC = Class.forName("org.mtr.core.tool.Angle", true, cl);
        Class<?> vecC = Class.forName("org.mtr.core.tool.Vector", true, cl);
        String[] N = {"E","SEE","SE","SSE","S","SSW","SW","SWW","W","NWW","NW","NNW","N","NNE","NE","NEE"};
        long x1 = Long.parseLong(a[1]), z1 = Long.parseLong(a[2]);
        long x2 = Long.parseLong(a[4]), z2 = Long.parseLong(a[5]);
        Object a1 = Enum.valueOf((Class) angleC, N[Integer.parseInt(a[3])]);
        Object a2 = Enum.valueOf((Class) angleC, N[Integer.parseInt(a[6])]);
        Field rad = angleC.getField("angleRadians");
        Field deg = angleC.getField("angleDegrees");
        Method rotY = vecC.getMethod("rotateY", double.class);
        Object v = vecC.getConstructor(double.class, double.class, double.class)
                .newInstance((double) (x2 - x1), 0.0, (double) (z2 - z1));
        Object rv = rotY.invoke(v, (float) rad.getDouble(a1));
        double sx = (Double) vecC.getMethod("x").invoke(rv);
        double sz = (Double) vecC.getMethod("z").invoke(rv);
        System.out.println("angle1 deg=" + deg.get(a1) + " rad=" + rad.getDouble(a1));
        System.out.println("angle2 deg=" + deg.get(a2) + " rad=" + rad.getDouble(a2));
        System.out.println("deltaSide(x')=" + sx + " deltaForward(z')=" + sz);
        System.out.println("angleForward=" + Math.atan2(sz, sx));
        Method sub = angleC.getMethod("sub", angleC);
        Object diff = sub.invoke(a2, a1);
        System.out.println("diff=" + diff + " deg=" + deg.get(diff) + " rad=" + rad.getDouble(diff));
        System.out.println("cos(2*a1)=" + Math.cos(2 * rad.getDouble(a1)));
    }
}
