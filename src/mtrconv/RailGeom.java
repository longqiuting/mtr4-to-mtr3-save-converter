package mtrconv;

/**
 * Straight port of MTR4's {@code org.mtr.core.data.RailMath} constructor, which is
 * byte-for-byte equivalent to MTR3's {@code mtr.data.Rail(BlockPos, RailAngle, ...)}.
 * Both were verified against the real jars in this project (see README).
 *
 * <p>MTR angles run clockwise from East: E=0, S=90, W=180, N=270, in 22.5° steps.</p>
 */
public final class RailGeom {

    public static final String[] ANGLE_NAMES = {
            "E", "SEE", "SE", "SSE", "S", "SSW", "SW", "SWW",
            "W", "NWW", "NW", "NNW", "N", "NNE", "NE", "NEE"
    };

    private static final double[] DEG = new double[16];
    private static final double[] RAD = new double[16];
    private static final double[] SIN = new double[16];
    private static final double[] COS = new double[16];
    private static final double[] TAN = new double[16];
    private static final double[] HALF_TAN = new double[16];

    private static final double ACCEPT_THRESHOLD = 1.0E-4;

    static {
        for (int i = 0; i < 16; i++) {
            // MTR normalizes angleDegrees into [-180, 180) — N (270°) becomes -90°.
            // This matters: sin/cos are unchanged but angleRadians and tan() signs are not,
            // and RailMath compares angleDifference against atan2 results.
            double normalized = i * 22.5;
            while (normalized >= 180.0) normalized -= 360.0;
            DEG[i] = normalized;
            RAD[i] = Math.toRadians(normalized);
            SIN[i] = Math.sin(RAD[i]);
            COS[i] = Math.cos(RAD[i]);
            TAN[i] = Math.tan(RAD[i]);
            HALF_TAN[i] = Math.tan(RAD[i] / 2.0);
        }
    }

    private RailGeom() {
    }

    /* --------------------------------------------------------------- angles */

    public static int angleIndex(String name) {
        for (int i = 0; i < ANGLE_NAMES.length; i++) if (ANGLE_NAMES[i].equals(name)) return i;
        return -1;
    }

    public static int opposite(int a) {
        return (a + 8) % 16;
    }

    public static boolean isParallel(int a, int b) {
        return a == b || a == opposite(b);
    }

    private static double normalizeDegrees(double deg) {
        double d = deg;
        while (d < -180.0) d += 360.0;
        while (d >= 180.0) d -= 360.0;
        return d;
    }

    /** Mirrors {@code Angle.fromAngle(float)}. */
    public static int fromDegrees(double deg) {
        double normalized = normalizeDegrees(deg);
        int q = (int) Math.round((normalized + 360.0) / 22.5) % 16;
        return (q + 16) % 16;
    }

    public static int fromRadians(double rad) {
        return fromDegrees(Math.toDegrees(rad));
    }

    private static int add(int a, int b) {
        return fromDegrees(DEG[a] + DEG[b]);
    }

    private static int sub(int a, int b) {
        return fromDegrees(DEG[a] - DEG[b]);
    }

    /** {@code facingStart = getRailAngle(false)} — derived from the stored h/k. */
    public static int facingFromHk(double h, double k) {
        return fromDegrees(Math.toDegrees(Math.atan2(k, h)));
    }

    /* ------------------------------------------------------------- geometry */

    /** Result holder mirroring MTR's Rail private fields. */
    public static final class Params {
        public double h1, k1, r1, tStart1, tEnd1;
        public double h2, k2, r2, tStart2, tEnd2;
        public int yStart, yEnd;
        public boolean reverseT1, isStraight1, reverseT2, isStraight2;

        public double getLength() {
            return Math.abs(tEnd2 - tStart2) + Math.abs(tEnd1 - tStart1);
        }

        /** Mirrors {@code RailMath.getPositionXZ}. */
        public double[] positionXZ(double value, double radiusOffset, boolean first) {
            double h = first ? h1 : h2;
            double k = first ? k1 : k2;
            double r = first ? r1 : r2;
            boolean straight = first ? isStraight1 : isStraight2;
            boolean reverse = first ? reverseT1 : reverseT2;
            double tStart = first ? tStart1 : tStart2;
            double t = (reverse ? -1 : 1) * value + tStart;
            if (straight) {
                return new double[]{
                        h * t + k * ((Math.abs(h) >= 0.5 && Math.abs(k) >= 0.5 ? 0.0 : r) + radiusOffset) + 0.5,
                        k * t + h * (r - radiusOffset) + 0.5
                };
            }
            return new double[]{
                    h + (r + radiusOffset) * Math.cos(t / r) + 0.5,
                    k + (r + radiusOffset) * Math.sin(t / r) + 0.5
            };
        }

        /** Position at a distance along the rail, in block coordinates (x, z). */
        public double[] positionAt(double value) {
            double count1 = Math.abs(tEnd1 - tStart1);
            double v = Math.max(0, Math.min(getLength(), value));
            if (v <= count1) return positionXZ(v, 0, true);
            return positionXZ(v - count1, 0, false);
        }
    }

    private static double tBounds(double x, double h, double z, double k, double r) {
        return Math.atan2(z - k, x - h) * r;
    }

    private static double tBounds(double x, double h, double z, double k, double r, double tStart, boolean reverse) {
        double t = tBounds(x, h, z, k, r);
        if (t < tStart && !reverse) return t + Math.PI * 2 * r;
        if (t > tStart && reverse) return t - Math.PI * 2 * r;
        return t;
    }

    /**
     * Port of the RailMath constructor. {@code verticalRadius} is not used for the
     * horizontal parameter set (it is stored separately by MTR3).
     */
    public static Params compute(double xStart, int yStart, double zStart,
                                 int angle1,
                                 double xEnd, int yEnd, double zEnd,
                                 int angle2) {
        Params p = new Params();
        p.yStart = yStart;
        p.yEnd = yEnd;

        double dx = xEnd - xStart;
        double dz = zEnd - zStart;
        double rotAngle = (float) RAD[angle1];
        double rc = Math.cos(rotAngle);
        double rs = Math.sin(rotAngle);
        double deltaSide = dx * rc + dz * rs;
        double deltaForward = dz * rc - dx * rs;

        if (isParallel(angle1, angle2)) {
            if (Math.abs(deltaForward) < ACCEPT_THRESHOLD) {
                p.h1 = COS[angle1];
                p.k1 = SIN[angle1];
                if (Math.abs(p.h1) >= 0.5 && Math.abs(p.k1) >= 0.5) {
                    p.r1 = (p.h1 * zStart - p.k1 * xStart) / p.h1 / p.h1;
                    p.tStart1 = xStart / p.h1;
                    p.tEnd1 = xEnd / p.h1;
                } else {
                    double div = COS[add(angle1, angle1)];
                    p.r1 = (p.h1 * zStart - p.k1 * xStart) / div;
                    p.tStart1 = (p.h1 * xStart - p.k1 * zStart) / div;
                    p.tEnd1 = (p.h1 * xEnd - p.k1 * zEnd) / div;
                }
                p.h2 = 0;
                p.k2 = 0;
                p.r2 = 0;
                p.tStart2 = 0;
                p.tEnd2 = 0;
                p.reverseT1 = p.tStart1 > p.tEnd1;
                p.reverseT2 = false;
                p.isStraight1 = true;
                p.isStraight2 = true;
            } else if (Math.abs(deltaSide) > ACCEPT_THRESHOLD) {
                double radius = (deltaForward * deltaForward + deltaSide * deltaSide) / (4.0 * deltaForward);
                p.r1 = p.r2 = Math.abs(radius);
                p.h1 = xStart - radius * SIN[angle1];
                p.k1 = zStart + radius * COS[angle1];
                p.h2 = xEnd - radius * SIN[angle2];
                p.k2 = zEnd + radius * COS[angle2];
                p.reverseT1 = (deltaForward < 0.0) != (deltaSide < 0.0);
                p.reverseT2 = !p.reverseT1;
                p.tStart1 = tBounds(xStart, p.h1, zStart, p.k1, p.r1);
                p.tEnd1 = tBounds(xStart + dx / 2.0, p.h1, zStart + dz / 2.0, p.k1, p.r1, p.tStart1, p.reverseT1);
                p.tStart2 = tBounds(xStart + dx / 2.0, p.h2, zStart + dz / 2.0, p.k2, p.r2);
                p.tEnd2 = tBounds(xEnd, p.h2, zEnd, p.k2, p.r2, p.tStart2, p.reverseT2);
                p.isStraight1 = false;
                p.isStraight2 = false;
            } else {
                zero(p);
            }
        } else {
            int newAngle1 = deltaSide < -ACCEPT_THRESHOLD ? opposite(angle1) : angle1;
            // NOTE: this test uses the *original* (unrotated) delta, unlike newAngle1 above.
            int newAngle2 = COS[angle2] * dx + SIN[angle2] * dz < -ACCEPT_THRESHOLD ? opposite(angle2) : angle2;
            double angleForward = Math.atan2(deltaForward, deltaSide);
            int diffIndex = sub(newAngle2, newAngle1);
            double angleDifference = RAD[diffIndex];
            if (Math.signum(angleForward) == Math.signum(angleDifference)) {
                double absAngleForward = Math.abs(angleForward);
                if (absAngleForward - Math.abs(angleDifference / 2.0) < ACCEPT_THRESHOLD) {
                    double offsetSide = Math.abs(deltaForward / HALF_TAN[diffIndex]);
                    double remainingSide = deltaSide - offsetSide;
                    double deltaXEnd = xStart + remainingSide * COS[newAngle1];
                    double deltaZEnd = zStart + remainingSide * SIN[newAngle1];
                    p.h1 = COS[newAngle1];
                    p.k1 = SIN[newAngle1];
                    if (Math.abs(p.h1) >= 0.5 && Math.abs(p.k1) >= 0.5) {
                        p.r1 = (p.h1 * zStart - p.k1 * xStart) / p.h1 / p.h1;
                        p.tStart1 = xStart / p.h1;
                        p.tEnd1 = deltaXEnd / p.h1;
                    } else {
                        double div = COS[add(newAngle1, newAngle1)];
                        p.r1 = (p.h1 * zStart - p.k1 * xStart) / div;
                        p.tStart1 = (p.h1 * xStart - p.k1 * zStart) / div;
                        p.tEnd1 = (p.h1 * deltaXEnd - p.k1 * deltaZEnd) / div;
                    }
                    p.isStraight1 = true;
                    p.reverseT1 = p.tStart1 > p.tEnd1;
                    double radius = deltaForward / (1.0 - COS[diffIndex]);
                    p.r2 = Math.abs(radius);
                    p.h2 = deltaXEnd - radius * SIN[newAngle1];
                    p.k2 = deltaZEnd + radius * COS[newAngle1];
                    p.reverseT2 = deltaForward < 0.0;
                    p.tStart2 = tBounds(deltaXEnd, p.h2, deltaZEnd, p.k2, p.r2);
                    p.tEnd2 = tBounds(xEnd, p.h2, zEnd, p.k2, p.r2, p.tStart2, p.reverseT2);
                    p.isStraight2 = false;
                } else if (absAngleForward - Math.abs(angleDifference) < ACCEPT_THRESHOLD) {
                    double crossSide = deltaForward / TAN[diffIndex];
                    double remainingSide = (deltaSide - crossSide) * (1.0 + COS[diffIndex]);
                    double remainingForward = (deltaSide - crossSide) * SIN[diffIndex];
                    double deltaXEnd = xStart + remainingSide * COS[newAngle1] - remainingForward * SIN[newAngle1];
                    double deltaZEnd = zStart + remainingSide * SIN[newAngle1] + remainingForward * COS[newAngle1];
                    double radius = (deltaSide - deltaForward / TAN[diffIndex]) / HALF_TAN[diffIndex];
                    p.r1 = Math.abs(radius);
                    p.h1 = xStart - radius * SIN[newAngle1];
                    p.k1 = zStart + radius * COS[newAngle1];
                    p.isStraight1 = false;
                    p.reverseT1 = deltaForward < 0.0;
                    p.tStart1 = tBounds(xStart, p.h1, zStart, p.k1, p.r1);
                    p.tEnd1 = tBounds(deltaXEnd, p.h1, deltaZEnd, p.k1, p.r1, p.tStart1, p.reverseT1);
                    p.h2 = COS[newAngle2];
                    p.k2 = SIN[newAngle2];
                    if (Math.abs(p.h2) >= 0.5 && Math.abs(p.k2) >= 0.5) {
                        p.r2 = (p.h2 * deltaZEnd - p.k2 * deltaXEnd) / p.h2 / p.h2;
                        p.tStart2 = deltaXEnd / p.h2;
                        p.tEnd2 = xEnd / p.h2;
                    } else {
                        double div = COS[add(newAngle2, newAngle2)];
                        p.r2 = (p.h2 * deltaZEnd - p.k2 * deltaXEnd) / div;
                        p.tStart2 = (p.h2 * deltaXEnd - p.k2 * deltaZEnd) / div;
                        p.tEnd2 = (p.h2 * xEnd - p.k2 * zEnd) / div;
                    }
                    p.isStraight2 = true;
                    p.reverseT2 = p.tStart2 > p.tEnd2;
                } else {
                    zero(p);
                }
            } else {
                zero(p);
            }
        }
        return p;
    }

    private static void zero(Params p) {
        p.h1 = p.k1 = p.r1 = 0;
        p.h2 = p.k2 = p.r2 = 0;
        p.tStart1 = p.tEnd1 = p.tStart2 = p.tEnd2 = 0;
        p.reverseT1 = p.reverseT2 = false;
        p.isStraight1 = p.isStraight2 = true;
    }

    /** True when the computed parameters reconstruct both endpoints within tolerance. */
    public static boolean endpointCheck(Params p, double x1, double z1, double x2, double z2, double tolerance) {
        double[] a = p.positionAt(0);
        double[] b = p.positionAt(p.getLength());
        double da = Math.hypot(a[0] - (x1 + 0.5), a[1] - (z1 + 0.5));
        double db = Math.hypot(b[0] - (x2 + 0.5), b[1] - (z2 + 0.5));
        return da <= tolerance && db <= tolerance;
    }
}
