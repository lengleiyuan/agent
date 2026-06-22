package cd.lan1akea.core.util;

/**
 * 字符串工具类。
 */
public final class StringUtils {

    private StringUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 判断字符串是否为空或空白。
     *
     * @param str 待判断字符串
     * @return true 如果为空或仅包含空白字符
     */
    public static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    /**
     * 判断字符串是否非空且包含非空白字符。
     *
     * @param str 待判断字符串
     * @return true 如果有非空白内容
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    /**
     * 判断字符串是否为null或空字符串。
     *
     * @param str 待判断字符串
     * @return true 如果为null或空字符串
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * 安全截断字符串。
     *
     * @param str      原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }

    /**
     * 安全截断字符串，超出部分加省略号。
     *
     * @param str      原始字符串
     * @param maxLength 最大长度（含省略号）
     * @return 截断后的字符串
     */
    public static String truncateWithEllipsis(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        if (maxLength <= 3) {
            return "...".substring(0, maxLength);
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * 如果字符串为null则返回默认值。
     *
     * @param str        原始字符串
     * @param defaultStr 默认值
     * @return 非null字符串
     */
    public static String defaultIfNull(String str, String defaultStr) {
        return str != null ? str : defaultStr;
    }

    /**
     * 判断是否包含中文。
     *
     * @param str 待判断字符串
     * @return true 如果包含中文字符
     */
    public static boolean containsChinese(String str) {
        if (str == null) {
            return false;
        }
        for (char c : str.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }
}
