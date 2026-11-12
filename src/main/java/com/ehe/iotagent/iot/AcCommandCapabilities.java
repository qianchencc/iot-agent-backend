package com.ehe.iotagent.iot;

import java.util.List;
import java.util.Set;

/**
 * One runtime-verified set of AC targets. Integer whole-degree setpoints only:
 * the gateway silently truncates fractional values. Auto mode and auto fan are
 * observation states without dispatch templates, so they are not offered.
 */
public final class AcCommandCapabilities {
    public static final double TEMPERATURE_MIN = 16;
    public static final double TEMPERATURE_MAX = 30;
    public static final double TEMPERATURE_STEP = 1;
    public static final List<String> MODES = List.of("cool", "heat", "dry", "fan");
    public static final List<String> FAN_SPEEDS = List.of("low", "high");
    public static final Set<String> POWER = Set.of("on", "off");
    public static final Set<String> MODE_SET = Set.copyOf(MODES);
    public static final Set<String> FAN_SPEED_SET = Set.copyOf(FAN_SPEEDS);

    private AcCommandCapabilities() {
    }

    public static DeviceCapabilityInspection.MapParameters parameters() {
        return new DeviceCapabilityInspection.MapParameters(
                new DeviceCapabilityInspection.Temperature(TEMPERATURE_MIN, TEMPERATURE_MAX, TEMPERATURE_STEP),
                MODES, FAN_SPEEDS);
    }
}
