package mtrconv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT support (zero dependencies).
 *
 * <p>Only two things are needed:</p>
 * <ol>
 *   <li>read {@code DataVersion} out of {@code level.dat} (so the generated
 *       {@code mtr_train_data.dat} carries the same data version as the world),</li>
 *   <li>write the tiny persistent-state file that makes MTR3 read its
 *       {@code mtr/} file tree at all (see {@link #writeTrainData(Path, int, boolean)}).</li>
 * </ol>
 */
public final class Nbt {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    /** Fallback data version: Minecraft 1.20.1 (the version MTR3 targets here). */
    public static final int DEFAULT_DATA_VERSION = 3465;

    private Nbt() {
    }

    /* ================================================================ reading */

    private static int u8(byte[] b, int p) {
        return b[p] & 0xFF;
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int i32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static int skip(byte[] b, int p, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                return p + 1;
            case TAG_SHORT:
                return p + 2;
            case TAG_INT:
            case TAG_FLOAT:
                return p + 4;
            case TAG_LONG:
            case TAG_DOUBLE:
                return p + 8;
            case TAG_BYTE_ARRAY: {
                int n = i32(b, p);
                return p + 4 + Math.max(0, n);
            }
            case TAG_STRING: {
                int n = u16(b, p);
                return p + 2 + Math.max(0, n) * 2;
            }
            case TAG_LIST: {
                int et = u8(b, p);
                int n = i32(b, p + 1);
                int q = p + 5;
                for (int i = 0; i < n; i++) q = skip(b, q, et);
                return q;
            }
            case TAG_COMPOUND: {
                int q = p + 1;
                while (true) {
                    int t = u8(b, q);
                    if (t == TAG_END) return q + 1;
                    int nl = u16(b, q + 1);
                    q = skip(b, q + 3 + nl, t);
                }
            }
            case TAG_INT_ARRAY: {
                int n = i32(b, p);
                return p + 4 + Math.max(0, n) * 4;
            }
            case TAG_LONG_ARRAY: {
                int n = i32(b, p);
                return p + 4 + Math.max(0, n) * 8;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    /** Finds a direct child of the compound whose first entry sits at {@code p}. */
    private static int[] child(byte[] b, int p, String name) throws IOException {
        while (true) {
            int t = u8(b, p);
            if (t == TAG_END) return null;
            int nl = u16(b, p + 1);
            String n = new String(b, p + 3, nl, StandardCharsets.UTF_8);
            int value = p + 3 + nl;
            if (n.equals(name)) return new int[]{t, value};
            p = skip(b, value, t);
        }
    }

    /** Offset of the first entry of the root compound (skips tag type + empty name). */
    private static int rootBody(byte[] b) throws IOException {
        if (b.length < 3) throw new IOException("file too short for NBT");
        if (u8(b, 0) != TAG_COMPOUND) throw new IOException("root tag is not a compound");
        int nl = u16(b, 1);
        return 3 + nl;
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(512, raw.length * 3))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Reads {@code DataVersion} from a {@code level.dat} (4-byte header + gzip NBT).
     * Returns {@link #DEFAULT_DATA_VERSION} when it cannot be determined.
     */
    public static int readDataVersion(Path levelDat) {
        try {
            byte[] raw = Files.readAllBytes(levelDat);
            byte[] nbt = null;
            if (raw.length > 8) {
                try {
                    nbt = gunzip(java.util.Arrays.copyOfRange(raw, 4, raw.length));
                } catch (IOException ignored) {
                    nbt = null;
                }
            }
            if (nbt == null) {
                try {
                    nbt = gunzip(raw);
                } catch (IOException ignored) {
                    return DEFAULT_DATA_VERSION;
                }
            }
            int[] hit = child(nbt, rootBody(nbt), "DataVersion");
            if (hit != null && hit[0] == TAG_INT) return i32(nbt, hit[1]);
        } catch (Exception ignored) {
            // fall through
        }
        return DEFAULT_DATA_VERSION;
    }

    /** True if the (gzipped) NBT file contains {@code data.raw_message_pack}. */
    public static boolean hasRawMessagePack(Path gzNbt) {
        try {
            byte[] nbt = gunzip(Files.readAllBytes(gzNbt));
            int[] data = child(nbt, rootBody(nbt), "data");
            if (data == null || data[0] != TAG_COMPOUND) return false;
            return child(nbt, data[1], "raw_message_pack") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Root keys of a gzipped NBT file, in file order ("name:type" strings). */
    public static java.util.List<String> rootKeys(Path gzNbt) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            byte[] nbt = gunzip(Files.readAllBytes(gzNbt));
            int p = rootBody(nbt);
            while (true) {
                int t = u8(nbt, p);
                if (t == TAG_END) break;
                int nl = u16(nbt, p + 1);
                String n = new String(nbt, p + 3, nl, StandardCharsets.UTF_8);
                out.add(n + ":" + t);
                p = skip(nbt, p + 3 + nl, t);
            }
        } catch (Exception ignored) {
            // return what we have
        }
        return out;
    }

    /* ================================================================ writing */

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.write(b, 0, b.length);
    }

    /**
     * Writes {@code <world>/data/mtr_train_data.dat} (or the per-dimension copy).
     *
     * <p>This is the file MTR3's {@code RailwayData.getInstance(world)} looks up by the
     * name {@code mtr_train_data}. Its {@code PersistentState} reader is the
     * <em>only</em> thing that calls {@code RailwayData.load(tag)} → {@code
     * RailwayDataFileSaveModule.load()}, i.e. the only code path that scans
     * {@code mtr/<namespace>/<dimension>/<kind>/...}. If the file is missing,
     * Minecraft instantiates the state through the supplier without ever calling
     * {@code load(tag)}, so MTR3 starts with a completely empty railway and the
     * whole converted tree is ignored.</p>
     *
     * <p>Content matches what MTR3 itself writes for a world that has no inline
     * snapshot: {@code {DataVersion:<int>, data:{use_time_and_wind_sync:<byte>}}}.
     * Deliberately <b>no</b> {@code raw_message_pack}: that key would make MTR3
     * load from the inline blob and ignore the files again.</p>
     */
    public static void writeTrainData(Path file, int dataVersion, boolean useTimeAndWindSync) throws IOException {
        ByteArrayOutputStream nbt = new ByteArrayOutputStream(64);
        nbt.write(TAG_COMPOUND);
        writeString(nbt, "");

        nbt.write(TAG_INT);
        writeString(nbt, "DataVersion");
        nbt.write((dataVersion >>> 24) & 0xFF);
        nbt.write((dataVersion >>> 16) & 0xFF);
        nbt.write((dataVersion >>> 8) & 0xFF);
        nbt.write(dataVersion & 0xFF);

        nbt.write(TAG_COMPOUND);
        writeString(nbt, "data");
        nbt.write(TAG_BYTE);
        writeString(nbt, "use_time_and_wind_sync");
        nbt.write(useTimeAndWindSync ? 1 : 0);
        nbt.write(TAG_END); // end of "data"

        nbt.write(TAG_END); // end of root

        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(file))) {
            gz.write(nbt.toByteArray());
        }
    }
}
