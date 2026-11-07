package mtrconv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Util {

    private Util() {
    }

    /* ------------------------------------------------------------- map access */

    public static Map<String, Object> map(Map<String, Object> src, String key) {
        Object o = src.get(key);
        return o instanceof Map ? MsgPack.cast(o) : new LinkedHashMap<>();
    }

    public static List<Object> list(Map<String, Object> src, String key) {
        Object o = src.get(key);
        return o instanceof List ? castList(o) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return (List<Object>) o;
    }

    public static long getLong(Map<String, Object> src, String key, long def) {
        Object o = src.get(key);
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int getInt(Map<String, Object> src, String key, int def) {
        return (int) getLong(src, key, def);
    }

    public static double getDouble(Map<String, Object> src, String key, double def) {
        Object o = src.get(key);
        if (o instanceof Number) return ((Number) o).doubleValue();
        return def;
    }

    public static boolean getBool(Map<String, Object> src, String key, boolean def) {
        Object o = src.get(key);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    public static String getString(Map<String, Object> src, String key, String def) {
        Object o = src.get(key);
        if (o == null) return def;
        return o instanceof String ? (String) o : String.valueOf(o);
    }

    /** Reads a coordinate from either a nested {x,y,z} map or a packed long. */
    public static long[] position(Map<String, Object> src, String key) {
        Object o = src.get(key);
        if (o instanceof Map) {
            Map<String, Object> m = MsgPack.cast(o);
            return new long[]{getLong(m, "x", 0), getLong(m, "y", 0), getLong(m, "z", 0)};
        }
        if (o instanceof Number) return unpack(((Number) o).longValue());
        return new long[]{0, 0, 0};
    }

    public static boolean hasPosition(Map<String, Object> src, String key) {
        Object o = src.get(key);
        return o instanceof Map || o instanceof Number;
    }

    /* ----------------------------------------------------------- BlockPos pack */

    /** Mirrors net.minecraft.core.BlockPos#asLong. */
    public static long pack(long x, long y, long z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    public static long[] unpack(long value) {
        long x = (value >> 38) & 0x3FFFFFFL;
        long z = (value >> 12) & 0x3FFFFFFL;
        long y = value & 0xFFFL;
        if (x >= 0x2000000L) x -= 0x4000000L;
        if (z >= 0x2000000L) z -= 0x4000000L;
        if (y >= 0x800L) y -= 0x1000L;
        return new long[]{x, y, z};
    }

    /* ------------------------------------------------------------------ names */

    /** MTR4 object file name: uppercase hex of the 64-bit id. */
    public static String mtr4FileName(long id) {
        return Long.toHexString(id).toUpperCase(Locale.ROOT);
    }

    /** MTR4 bucket directory: the low byte as 2-digit uppercase hex. */
    public static String mtr4Bucket(long id) {
        return String.format(Locale.ROOT, "%02X", id & 0xFF);
    }

    /** MTR3 bucket directory: {@code id % 100} (see RailwayDataFileSaveModule). */
    public static String mtr3Bucket(long id) {
        return Long.toString(id % 100L);
    }

    public static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
