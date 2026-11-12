package com.ehe.iotagent.admin;

import java.util.List;

/**
 * Single authoritative description of one administrable setting: default, unit,
 * range, effect mode, real consumer and deployment key. Served inside the
 * configuration view so the console never keeps a second field description.
 */
public record AdminSettingField(String name, String label, String group, String kind, String unit,
        Object defaultValue, Long min, Long max, List<String> options, String effect,
        boolean secret, String springKey, String consumer, String note) {

    /** Effect modes: live = next run/cycle; restart = supervised process restart; readonly = deployment only. */
    public static final String LIVE = "live";
    public static final String RESTART = "restart";
    public static final String READONLY = "readonly";
}
