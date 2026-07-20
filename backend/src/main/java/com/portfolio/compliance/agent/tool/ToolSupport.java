package com.portfolio.compliance.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSupport {

    private ToolSupport() {
    }

    public static String str(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    public static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    public static Long longValue(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Integer intValue(Map<String, Object> args, String key, int defaultValue) {
        String value = str(args, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
