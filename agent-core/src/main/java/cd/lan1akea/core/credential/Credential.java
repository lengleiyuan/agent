package cd.lan1akea.core.credential;

/**
 * 凭证接口。
 * <p>
 * 各种凭证（API Key、OAuth Token等）的统一抽象。
 * </p>
 */
public interface Credential {

    /** @return 凭证类型 */
    String getType();

    /** @return 序列化后的凭证值 */
    String getValue();

    /** @return 是否已过期 */
    boolean isExpired();
}
