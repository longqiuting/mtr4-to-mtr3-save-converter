package mtrconv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

/**
 * Detects which MTR version an instance is running by reading {@code mods/*.jar}
 * metadata (fabric.mod.json or mods.toml) rather than trusting file names.
 */
public final class Versions {

    public static final class Mod {
        public final String file;
        public final String id;
        public final String name;
        public final String version;
        public final int major;

        Mod(String file, String id, String name, String version) {
            this.file = file;
            this.id = id;
            this.name = name;
            this.version = version;
            this.major = parseMajor(version);
        }

        public String describe() {
            return file + " (" + name + " " + version + ")";
        }
    }

    private Versions() {
    }

    private static int parseMajor(String version) {
        Matcher m = Pattern.compile("(\\d+)\\.").matcher(version == null ? "" : version);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /** Returns the mod whose id is exactly "mtr", or null. */
    public static Mod findMtr(Path instanceRoot) {
        Path mods = instanceRoot.resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        File[] jars = mods.toFile().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return null;
        for (File jar : jars) {
            Mod mod = readMod(jar);
            if (mod != null) return mod;
        }
        return null;
    }

    private static Mod readMod(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                String text = readAll(zip.getInputStream(fabric));
                String id = grab(text, "\"id\"\\s*:\\s*\"([^\"]+)\"");
                if ("mtr".equals(id)) {
                    String version = grab(text, "\"version\"\\s*:\\s*\"([^\"]+)\"");
                    String name = grab(text, "\"name\"\\s*:\\s*\"([^\"]+)\"");
                    return new Mod(jar.getName(), id, name == null ? "MTR" : name, version == null ? "?" : version);
                }
            }
            ZipEntry forge = zip.getEntry("META-INF/mods.toml");
            if (forge != null) {
                String text = readAll(zip.getInputStream(forge));
                // only the `[[mods]]` block declares this jar's own id; dependency
                // blocks also contain modId="mtr" and must not be mistaken for it
                int modsBlock = text.indexOf("[[mods]]");
                if (modsBlock >= 0) {
                    String block = text.substring(modsBlock, Math.min(text.length(), modsBlock + 4000));
                    String id = grab(block, "modId\\s*=\\s*\"([^\"]+)\"");
                    if ("mtr".equals(id)) {
                        String version = grab(block, "version\\s*=\\s*\"([^\"]+)\"");
                        String name = grab(block, "displayName\\s*=\\s*\"([^\"]+)\"");
                        return new Mod(jar.getName(), id, name == null ? "MTR" : name, version == null ? "?" : version);
                    }
                }
            }
        } catch (Exception ignored) {
            // not a readable jar — skip
        }
        return null;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String grab(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Whitelist policy: only the MTR versions whose data schemas were verified are
     * silently accepted. Anything else converts with a loud warning instead of
     * failing, because the mapping is keyed on field names that have been stable
     * across 4.0.x → 4.0.y.
     */
    public static final String[] KNOWN_SOURCE = {"4.0.5"};
    public static final String[] KNOWN_TARGET = {"3.6.4.2"};

    public static boolean isKnown(String[] whitelist, String version) {
        if (version == null) return false;
        for (String v : whitelist) {
            // fabric/mod jars often report "1.20.1-3.6.4.2" or "v4.0.5+1.20.1"
            if (version.equals(v) || version.endsWith("-" + v) || version.endsWith("+" + v)
                    || version.endsWith(v)) return true;
        }
        return false;
    }

    /** Walks up from a save directory until it finds the instance root (the one with mods/). */
    public static Path findInstanceRoot(Path saveOrInstance) {
        Path p = saveOrInstance;
        for (int i = 0; i < 4 && p != null; i++) {
            if (Files.isDirectory(p.resolve("mods"))) return p;
            p = p.getParent();
        }
        return saveOrInstance;
    }

    static List<String> filesUnder(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).sorted().forEach(p -> out.add(p.toString()));
        } catch (IOException ignored) {
            // ignore unreadable subtree
        }
        return out;
    }

    static Path path(String s) {
        return Paths.get(s);
    }
}
