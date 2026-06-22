package cd.lan1akea.core.credential;

/**
 * API Key 凭证。
 */
public class ApiKeyCredential implements Credential {

    private final String provider;
    private final String apiKey;

    public ApiKeyCredential(String provider, String apiKey) {
        this.provider = provider;
        this.apiKey = apiKey;
    }

    @Override
    public String getType() { return "api_key"; }

    @Override
    public String getValue() { return apiKey; }

    @Override
    public boolean isExpired() { return false; }

    public String getProvider() { return provider; }
}
