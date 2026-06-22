package cd.lan1akea.core.tool;

/**
 * 已注册工具函数描述符。
 * <p>
 * 记录通过注解扫描发现并注册的工具方法的元数据。
 * </p>
 */
public class RegisteredToolFunction {

    /** 工具名称 */
    private final String name;

    /** 工具描述 */
    private final String description;

    /** 所属类名 */
    private final String className;

    /** 方法名 */
    private final String methodName;

    /** 参数数量 */
    private final int paramCount;

    /** 注册时间戳 */
    private final long registeredAt;

    public RegisteredToolFunction(String name, String description,
                                   String className, String methodName, int paramCount) {
        this.name = name;
        this.description = description;
        this.className = className;
        this.methodName = methodName;
        this.paramCount = paramCount;
        this.registeredAt = System.currentTimeMillis();
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public int getParamCount() { return paramCount; }
    public long getRegisteredAt() { return registeredAt; }
}
