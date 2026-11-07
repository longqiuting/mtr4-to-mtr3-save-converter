package mtrconv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free MessagePack reader/writer.
 * Value model: Map&lt;String,Object&gt; | List&lt;Object&gt; | String | byte[] | Long | Double | Boolean | null
 */
public final class MsgPack {

    private MsgPack() {
    }

    /* ------------------------------------------------------------------ read */

    public static final class Reader {
        private final byte[] b;
        private int p;

        public Reader(byte[] b) {
            this.b = b;
        }

        public int position() {
            return p;
        }

        public boolean hasMore() {
            return p < b.length;
        }

        public Object read() throws IOException {
            if (p >= b.length) throw new IOException("unexpected end of msgpack data");
            int c = b[p++] & 0xFF;
            if (c <= 0x7F) return (long) c;
            if (c >= 0xE0) return (long) (c - 256);
            if ((c & 0xF0) == 0x80) return readMap(c & 0x0F);
            if ((c & 0xF0) == 0x90) return readArray(c & 0x0F);
            if ((c & 0xE0) == 0xA0) return readString(c & 0x1F);
            switch (c) {
                case 0xC0: return null;
                case 0xC2: return Boolean.FALSE;
                case 0xC3: return Boolean.TRUE;
                case 0xC4: return readBin(u8());
                case 0xC5: return readBin(u16());
                case 0xC6: return readBin((int) u32());
                case 0xCA: return (double) Float.intBitsToFloat((int) u32());
                case 0xCB: return Double.longBitsToDouble(u64());
                case 0xCC: return (long) u8();
                case 0xCD: return (long) u16();
                case 0xCE: return u32();
                case 0xCF: return u64();
                case 0xD0: return (long) (byte) u8();
                case 0xD1: return (long) (short) u16();
                case 0xD2: return (long) (int) u32();
                case 0xD3: return u64();
                case 0xD9: return readString(u8());
                case 0xDA: return readString(u16());
                case 0xDB: return readString((int) u32());
                case 0xDC: return readArray(u16());
                case 0xDD: return readArray((int) u32());
                case 0xDE: return readMap(u16());
                case 0xDF: return readMap((int) u32());
                default:
                    throw new IOException("unsupported msgpack opcode 0x" + Integer.toHexString(c) + " at " + (p - 1));
            }
        }

        private int u8() throws IOException {
            if (p >= b.length) throw new IOException("unexpected end of msgpack data");
            return b[p++] & 0xFF;
        }

        private int u16() throws IOException {
            int v = (u8() << 8) | u8();
            return v;
        }

        private long u32() throws IOException {
            return ((long) u16() << 16) | u16();
        }

        private long u64() throws IOException {
            return (u32() << 32) | (u32() & 0xFFFFFFFFL);
        }

        private String readString(int len) throws IOException {
            if (p + len > b.length) throw new IOException("string overruns buffer");
            String s = new String(b, p, len, StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        private byte[] readBin(int len) throws IOException {
            if (len < 0 || p + len > b.length) throw new IOException("bin overruns buffer");
            byte[] out = new byte[len];
            System.arraycopy(b, p, out, 0, len);
            p += len;
            return out;
        }

        private List<Object> readArray(int n) throws IOException {
            List<Object> list = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) list.add(read());
            return list;
        }

        private Map<String, Object> readMap(int n) throws IOException {
            Map<String, Object> map = new LinkedHashMap<>(Math.max(4, (int) (n / 0.75f) + 1));
            for (int i = 0; i < n; i++) {
                Object k = read();
                Object v = read();
                map.put(String.valueOf(k), v);
            }
            return map;
        }
    }

    /**
     * MTR appends incremental fragments after the first complete document (the
     * whole object is written first, then nested values are appended as fields
     * change). The first complete document is the authoritative snapshot; the
     * trailing bytes are partial rewrites and are tolerated / ignored.
     */
    public static Map<String, Object> readObject(byte[] data) throws IOException {
        Reader reader = new Reader(data);
        Object first = reader.read();
        if (first instanceof Map) return cast(first);
        // Fallback: scan forward for the first complete map document.
        Reader scan = new Reader(data);
        while (scan.hasMore()) {
            Object v = scan.read();
            if (v instanceof Map) return cast(v);
        }
        throw new IOException("no MessagePack map found");
    }

    public static int trailingBytes(byte[] data) {
        Reader reader = new Reader(data);
        try {
            reader.read();
            return data.length - reader.position();
        } catch (IOException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    /* ----------------------------------------------------------------- write */

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        public Writer packLong(long v) {
            if (v >= 0) {
                if (v <= 0x7F) out.write((int) v);
                else if (v <= 0xFF) { out.write(0xCC); out.write((int) v); }
                else if (v <= 0xFFFF) { out.write(0xCD); out.write((int) (v >> 8)); out.write((int) v); }
                else if (v <= 0xFFFFFFFFL) {
                    out.write(0xCE);
                    out.write((int) (v >> 24)); out.write((int) (v >> 16)); out.write((int) (v >> 8)); out.write((int) v);
                } else {
                    out.write(0xCF);
                    for (int s = 56; s >= 0; s -= 8) out.write((int) (v >>> s));
                }
            } else {
                if (v >= -32) out.write((int) v);
                else if (v >= -128) { out.write(0xD0); out.write((int) v); }
                else if (v >= -32768) { out.write(0xD1); out.write((int) (v >> 8)); out.write((int) v); }
                else if (v >= -2147483648L) {
                    out.write(0xD2);
                    out.write((int) (v >> 24)); out.write((int) (v >> 16)); out.write((int) (v >> 8)); out.write((int) v);
                } else {
                    out.write(0xD3);
                    for (int s = 56; s >= 0; s -= 8) out.write((int) (v >>> s));
                }
            }
            return this;
        }

        public Writer packDouble(double v) {
            out.write(0xCB);
            long bits = Double.doubleToLongBits(v);
            for (int s = 56; s >= 0; s -= 8) out.write((int) (bits >>> s));
            return this;
        }

        /** 32-bit float — what MTR3 itself writes for rail_length / acceleration_constant. */
        public Writer packFloat(float v) {
            out.write(0xCA);
            int bits = Float.floatToIntBits(v);
            for (int s = 24; s >= 0; s -= 8) out.write((bits >>> s) & 0xFF);
            return this;
        }

        public Writer packBoolean(boolean v) {
            out.write(v ? 0xC3 : 0xC2);
            return this;
        }

        public Writer packString(String v) {
            byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
            int len = bytes.length;
            if (len <= 31) out.write(0xA0 | len);
            else if (len <= 0xFF) { out.write(0xD9); out.write(len); }
            else if (len <= 0xFFFF) { out.write(0xDA); out.write(len >> 8); out.write(len); }
            else { out.write(0xDB); out.write(len >>> 24); out.write(len >>> 16); out.write(len >>> 8); out.write(len); }
            out.write(bytes, 0, len);
            return this;
        }

        public Writer packMapHeader(int n) {
            if (n <= 15) out.write(0x80 | n);
            else if (n <= 0xFFFF) { out.write(0xDE); out.write(n >> 8); out.write(n); }
            else { out.write(0xDF); out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n); }
            return this;
        }

        public Writer packArrayHeader(int n) {
            if (n <= 15) out.write(0x90 | n);
            else if (n <= 0xFFFF) { out.write(0xDC); out.write(n >> 8); out.write(n); }
            else { out.write(0xDD); out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n); }
            return this;
        }

        public Writer pack(Object v) {
            if (v == null) {
                out.write(0xC0);
            } else if (v instanceof String) {
                packString((String) v);
            } else if (v instanceof Boolean) {
                packBoolean((Boolean) v);
            } else if (v instanceof Float) {
                packFloat((Float) v);
            } else if (v instanceof Double) {
                packDouble((Double) v);
            } else if (v instanceof Number) {
                packLong(((Number) v).longValue());
            } else if (v instanceof byte[]) {
                byte[] bin = (byte[]) v;
                out.write(0xC4);
                out.write(bin.length);
                out.write(bin, 0, bin.length);
            } else if (v instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) v;
                packMapHeader(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    packString(String.valueOf(e.getKey()));
                    pack(e.getValue());
                }
            } else if (v instanceof List) {
                List<?> list = (List<?>) v;
                packArrayHeader(list.size());
                for (Object o : list) pack(o);
            } else {
                throw new IllegalArgumentException("cannot pack " + v.getClass());
            }
            return this;
        }
    }
}
