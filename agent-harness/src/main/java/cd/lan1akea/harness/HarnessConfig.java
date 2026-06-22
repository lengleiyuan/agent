package cd.lan1akea.harness;

/**
 * 全局 SDK 配置。
 */
public class HarnessConfig {

    private boolean debugMode = false;
    private String defaultModelProvider = "openai";

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

    public String getDefaultModelProvider() { return defaultModelProvider; }
    public void setDefaultModelProvider(String defaultModelProvider) {
        this.defaultModelProvider = defaultModelProvider;
    }
}
