package kntr.base.localization;

public final class NumberFormat_androidKt {
    private NumberFormat_androidKt() {}

    public static String format(Long value) {
        return String.valueOf(value);
    }

    public static String format(String value) {
        return value;
    }

    public static String formatNumber(long value, String suffix) {
        return value + suffix;
    }

    public static String format$default(Long value, String suffix, int mask, Object marker) {
        return String.valueOf(value);
    }

    public static String format(Double value) {
        return String.valueOf(value);
    }

    public static int formatNumber(int value) {
        return value;
    }
}
