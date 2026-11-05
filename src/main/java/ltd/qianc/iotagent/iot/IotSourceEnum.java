package ltd.qianc.iotagent.iot;

import java.util.Locale;

/** The two gateway authorization domains; their scopes must never be merged. */
public enum IotSourceEnum {
    OFFICE("office"),
    COMPANY("company");

    private final String wireValue;

    IotSourceEnum(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static IotSourceEnum parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("IoT source is required");
        }
        for (IotSourceEnum source : values()) {
            if (source.wireValue.equals(value.toLowerCase(Locale.ROOT))) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unsupported IoT source: " + value);
    }
}
