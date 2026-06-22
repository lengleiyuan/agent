package cd.lan1akea.core.credential;

import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * 加密凭证存储。
 * <p>
 * 使用 AES 加密 API Key 后再持久化。
 * </p>
 */
public class EncryptedCredentialStore {

    private static final String ALGORITHM = "AES";
    private final SecretKeySpec keySpec;

    public EncryptedCredentialStore(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 加密明文。
     */
    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("凭证加密失败", e);
        }
    }

    /**
     * 解密密文。
     */
    public String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("凭证解密失败", e);
        }
    }
}
