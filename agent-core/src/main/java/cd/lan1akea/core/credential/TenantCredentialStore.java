package cd.lan1akea.core.credential;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 租户凭证存储接口。
 */
public interface TenantCredentialStore {

    /** 保存凭证 */
    Mono<Void> save(long tenantId, Credential credential);

    /** 获取租户的所有凭证 */
    Mono<List<Credential>> listByTenant(long tenantId);

    /** 按提供商获取凭证 */
    Mono<Credential> getByProvider(long tenantId, String provider);

    /** 删除凭证 */
    Mono<Void> delete(long tenantId, String provider);
}
