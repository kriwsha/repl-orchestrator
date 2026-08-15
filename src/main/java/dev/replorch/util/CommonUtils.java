package dev.replorch.util;

public class CommonUtils {

    private CommonUtils() {
        throw new UnsupportedOperationException();
    }

    public static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
