package mtrconv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The schema MTR3 3.6.4.2 itself writes, transcribed key-for-key from files produced by
 * the mod (saves {@code 测试摄影专用} and {@code 绫洛市} of the same instance) and from the
 * mod's own bytecode ({@code mtr.data.Rail(Map)}, {@code RailwayDataFileSaveModule$RailEntry}).
 *
 * <p>{@link #REQUIRED} = keys every MTR3-written object has; {@link #KEYS} = every key MTR3
 * (or its addons) may additionally write. Extra-key reports use {@code KEYS}, "missing"
 * reports use {@code REQUIRED}, so both an older and a newer MTR3 file pass.</p>
 */
public final class Schema {

    /** 'i' = integer (msgpack int), 'f' = float (msgpack float/double), 'n' = either, 'b' = bool, 's' = string, 'a' = array, 'm' = map. */
    private static final Map<String, Character> TYPE = new LinkedHashMap<>();
    private static final Map<String, String[]> KEYS = new LinkedHashMap<>();
    private static final Map<String, String[]> REQUIRED = new LinkedHashMap<>();

    static {
        put("id", 'i');
        put("transport_mode", 's');
        put("name", 's');
        put("color", 'i');
        put("x_min", 'i');
        put("z_min", 'i');
        put("x_max", 'i');
        put("z_max", 'i');
        put("zone", 'i');
        put("exits", 'm');
        put("pos_1", 'i');
        put("pos_2", 'i');
        put("dwell_time", 'i');
        put("adc_time", 'i');
        put("stop_without_opening_doors", 'b');
        put("psd_display_mode", 'i');
        put("platform_ids", 'a');
        put("custom_destinations", 'a');
        put("custom_dwell_times", 'a');
        put("platform_dwell_times", 'a');
        put("custom_adc_times", 'a');
        put("platform_adc_times", 'a');
        put("routes.stop_without_opening_doors", 'a');   // per-stop array on routes, bool elsewhere
        put("route_type", 's');
        put("is_light_rail_route", 'b');
        put("is_route_hidden", 'b');
        put("disable_next_station_announcements", 'b');
        put("light_rail_route_number", 's');
        put("circular_state", 's');
        put("route_ids", 'a');
        put("use_real_time", 'b');
        put("repeat_infinitely", 'b');
        put("cruising_altitude", 'i');
        put("frequencies", 'a');
        put("departures", 'a');
        put("deploy_index", 'i');
        put("rail_length", 'f');
        put("train_custom_id", 's');
        put("train_type", 's');
        put("unlimited_trains", 'b');
        put("max_trains", 'i');
        put("is_manual", 'b');
        put("max_manual_speed", 'i');
        put("enable_predictive_braking", 'b');
        put("repeat_index_1", 'i');
        put("repeat_index_2", 'i');
        put("acceleration_constant", 'f');
        put("path", 'a');
        put("trains", 'a');
        put("node_pos", 'i');
        put("rail_connections", 'a');
        put("h_1", 'n');
        put("k_1", 'n');
        put("h_2", 'n');
        put("k_2", 'n');
        put("r_1", 'n');
        put("r_2", 'n');
        put("t_start_1", 'f');
        put("t_end_1", 'f');
        put("t_start_2", 'f');
        put("t_end_2", 'f');
        put("y_start", 'i');
        put("y_end", 'i');
        put("reverse_t_1", 'b');
        put("is_straight_1", 'b');
        put("reverse_t_2", 'b');
        put("is_straight_2", 'b');
        put("rail_type", 's');
        put("rail_data", 's');   // 4-char "\u0000\u0000\u0000\u0000" (msgpack str, not bin)
        put("model_key", 's');
        put("is_secondary_dir", 'b');
        put("vertical_curve_radius", 'n');
        put("custom_configs", 's');
        put("roll_angle_map", 's');
        put("opening_direction", 'i');
        put("rolling_offset", 'f');

        KEYS.put("stations", new String[]{
                "id", "transport_mode", "name", "color",
                "x_min", "z_min", "x_max", "z_max", "zone", "exits"});
        REQUIRED.put("stations", KEYS.get("stations"));

        KEYS.put("platforms", new String[]{
                "id", "transport_mode", "name", "color",
                "pos_1", "pos_2", "dwell_time",
                "adc_time", "stop_without_opening_doors", "psd_display_mode"});
        REQUIRED.put("platforms", new String[]{
                "id", "transport_mode", "name", "color", "pos_1", "pos_2", "dwell_time"});

        KEYS.put("routes", new String[]{
                "id", "transport_mode", "name", "color",
                "platform_ids", "custom_destinations",
                "stop_without_opening_doors", "custom_dwell_times", "platform_dwell_times",
                "custom_adc_times", "platform_adc_times",
                "route_type", "is_light_rail_route", "is_route_hidden",
                "disable_next_station_announcements", "light_rail_route_number", "circular_state"});
        // older MTR3 route files carry only these (verified against 测试摄影专用)
        REQUIRED.put("routes", new String[]{
                "id", "transport_mode", "name", "color",
                "platform_ids", "custom_destinations",
                "route_type", "is_light_rail_route", "is_route_hidden",
                "disable_next_station_announcements", "light_rail_route_number", "circular_state"});

        KEYS.put("depots", new String[]{
                "id", "transport_mode", "name", "color",
                "x_min", "z_min", "x_max", "z_max",
                "route_ids", "use_real_time", "repeat_infinitely", "cruising_altitude",
                "frequencies", "departures", "deploy_index"});
        REQUIRED.put("depots", KEYS.get("depots"));

        KEYS.put("sidings", new String[]{
                "id", "transport_mode", "name", "color",
                "pos_1", "pos_2", "dwell_time",
                "adc_time", "stop_without_opening_doors", "psd_display_mode",
                "rail_length", "train_custom_id", "train_type",
                "unlimited_trains", "max_trains", "is_manual", "max_manual_speed",
                "enable_predictive_braking", "repeat_index_1", "repeat_index_2",
                "acceleration_constant", "path", "trains"});
        REQUIRED.put("sidings", KEYS.get("sidings"));

        KEYS.put("rails", new String[]{"node_pos", "rail_connections"});
        REQUIRED.put("rails", KEYS.get("rails"));

        // per rail connection: MTR3 core reads exactly these (Rail(Map) + RailEntry),
        // the ANTE/NTE addons add the path_* / bezier_curve model-rail keys.
        String[] connRequired = {"node_pos", "h_1", "k_1", "h_2", "k_2", "r_1", "r_2",
                "t_start_1", "t_end_1", "t_start_2", "t_end_2", "y_start", "y_end",
                "reverse_t_1", "is_straight_1", "reverse_t_2", "is_straight_2",
                "rail_type", "transport_mode"};
        KEYS.put("rail_connections", new String[]{
                "node_pos", "h_1", "k_1", "h_2", "k_2", "r_1", "r_2",
                "t_start_1", "t_end_1", "t_start_2", "t_end_2", "y_start", "y_end",
                "reverse_t_1", "is_straight_1", "reverse_t_2", "is_straight_2",
                "rail_type", "transport_mode", "rail_data", "model_key", "is_secondary_dir",
                "vertical_curve_radius", "custom_configs", "roll_angle_map",
                "opening_direction", "rolling_offset",
                "path_mode", "pos_start", "facing_start", "pos_end", "facing_end", "bezier_curve"});
        REQUIRED.put("rail_connections", connRequired);

        KEYS.put("lifts", new String[]{
                "id", "transport_mode", "name", "color",
                "lift_height", "lift_width", "lift_depth",
                "lift_offset_x", "lift_offset_y", "lift_offset_z",
                "is_double_sided", "lift_style", "facing",
                "current_position_x", "current_position_y", "current_position_z",
                "riding_entities", "floors", "acceleration", "max_speed", "display_color",
                "fangsu_model_key"});
        REQUIRED.put("lifts", new String[]{
                "id", "transport_mode", "name", "color",
                "lift_height", "lift_width", "lift_depth",
                "lift_offset_x", "lift_offset_y", "lift_offset_z",
                "is_double_sided", "lift_style", "facing",
                "current_position_x", "current_position_y", "current_position_z",
                "riding_entities", "floors"});

        KEYS.put("signal-blocks", new String[]{"id", "transport_mode", "name", "color", "rails"});
        REQUIRED.put("signal-blocks", KEYS.get("signal-blocks"));
    }

    private static void put(String key, char t) {
        TYPE.put(key, t);
    }

    private Schema() {
    }

    public static String[] keys(String kind) {
        return KEYS.get(kind);
    }

    public static java.util.Set<String> kinds() {
        return KEYS.keySet();
    }

    /**
     * Compares one decoded object against the reference schema.
     *
     * @return human readable problems; empty list means "same keys/types as MTR3's own files"
     */
    public static List<String> check(String kind, Map<String, Object> obj) {
        List<String> problems = new ArrayList<>();
        if (!KEYS.containsKey(kind)) return problems;

        if ("rails".equals(kind)) {
            Object conns = obj.get("rail_connections");
            if (conns instanceof List) {
                int i = 0;
                for (Object c : (List<?>) conns) {
                    if (c instanceof Map) {
                        problems.addAll(checkKeys("rail_connections", MsgPack.cast(c), "rail_connections[" + i + "]"));
                    }
                    i++;
                }
            }
        }

        problems.addAll(checkKeys(kind, obj, kind));
        return problems;
    }

    private static List<String> checkKeys(String schemaName, Map<String, Object> obj, String where) {
        List<String> problems = new ArrayList<>();
        String[] allowed = KEYS.get(schemaName);
        String[] required = REQUIRED.getOrDefault(schemaName, allowed);
        if (allowed == null) return problems;

        for (String key : required) {
            if (!obj.containsKey(key)) problems.add(where + ": missing key '" + key + "'");
        }
        java.util.Set<String> allow = new java.util.HashSet<>(java.util.Arrays.asList(allowed));
        for (String key : obj.keySet()) {
            if (!allow.contains(key)) problems.add(where + ": extra key '" + key + "' (MTR3 never writes it)");
        }
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            Character t = TYPE.get(schemaName + "." + e.getKey());
            if (t == null) t = TYPE.get(e.getKey());
            if (t == null || e.getValue() == null) continue;
            if (!typeOk(t, e.getValue())) {
                problems.add(where + ": key '" + e.getKey() + "' has type " + e.getValue().getClass().getSimpleName()
                        + ", expected " + describe(t));
            }
        }
        return problems;
    }

    private static String describe(char t) {
        switch (t) {
            case 'i': return "integer";
            case 'f': return "float";
            case 'n': return "number";
            case 'b': return "boolean";
            case 's': return "string";
            case 'a': return "array";
            default: return "map";
        }
    }

    private static boolean typeOk(char t, Object v) {
        switch (t) {
            case 'i': return v instanceof Long;
            case 'f': return v instanceof Double || v instanceof Float;
            case 'n': return v instanceof Number;
            case 'b': return v instanceof Boolean;
            case 's': return v instanceof String;
            case 'a': return v instanceof List;
            default: return v instanceof Map;
        }
    }
}
