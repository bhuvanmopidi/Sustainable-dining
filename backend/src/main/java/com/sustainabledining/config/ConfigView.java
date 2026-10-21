package com.sustainabledining.config;

import java.util.List;
import java.util.Map;

/** Read-only, dotted-path access to an experiment config body, e.g. getInt("semester.weeks"). */
public final class ConfigView {
    private final Map<String, Object> body;

    public ConfigView(Map<String, Object> body) {
        this.body = body;
    }

    public Object get(String path) {
        Object node = body;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(key)) {
                throw new IllegalArgumentException("config has no '" + path + "'");
            }
            node = map.get(key);
        }
        return node;
    }

    public int getInt(String path) { return ((Number) get(path)).intValue(); }

    public double getDouble(String path) { return ((Number) get(path)).doubleValue(); }

    public List<String> getStringList(String path) {
        return ((List<?>) get(path)).stream().map(String::valueOf).toList();
    }
}