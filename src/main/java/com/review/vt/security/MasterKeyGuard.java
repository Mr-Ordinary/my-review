package com.review.vt.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 主密钥一致性自愈：启动时逐个尝试解密用户已保存的 API Key。
 *
 * <p>主密钥文件一旦丢失/被重新生成，旧密文就永远解不开（AES-GCM 的 tag 校验失败）。
 * 以前这种状态下每次调 LLM 都会抛「主密钥是否已更换」，页面连环报错。
 * 这里在启动时把**解不开的密文清空**，用户会看到「未配置 API Key」，重新填一次即可；
 * 同时在 app_meta 记录主密钥指纹，日志里能看出密钥是否换过。
 */
@Component
@Profile("!json")
@Order(0)
public class MasterKeyGuard implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyGuard.class);
    private static final String FP_KEY = "master_key.fingerprint";

    private final JdbcTemplate jdbc;
    private final MasterKeyService masterKey;

    public MasterKeyGuard(JdbcTemplate jdbc, MasterKeyService masterKey) {
        this.jdbc = jdbc;
        this.masterKey = masterKey;
    }

    @Override
    public void run(String... args) {
        String fp = AesGcmCrypto.fingerprint(masterKey.key());
        String saved = jdbc.query("SELECT value FROM app_meta WHERE key = ?",
                rs -> rs.next() ? rs.getString(1) : null, FP_KEY);
        if (saved == null) {
            jdbc.update("INSERT INTO app_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                    FP_KEY, fp);
        } else if (!saved.equals(fp)) {
            log.error("检测到主密钥已更换（指纹 {} → {}）。旧 API Key 无法解密，将自动清空，请到个人中心重新填写。",
                    saved.substring(0, Math.min(12, saved.length())),
                    fp.substring(0, Math.min(12, fp.length())));
            jdbc.update("INSERT INTO app_meta (key, value) VALUES (?, ?) " +
                    "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", FP_KEY, fp);
        }

        int cleared = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, api_key_nonce, api_key_cipher FROM users WHERE api_key_cipher IS NOT NULL");
        AesGcmCrypto crypto = new AesGcmCrypto(masterKey.key());
        for (Map<String, Object> r : rows) {
            String nonce = (String) r.get("api_key_nonce");
            String cipher = (String) r.get("api_key_cipher");
            try {
                crypto.decrypt(nonce, cipher);
            } catch (Exception e) {
                Long id = ((Number) r.get("id")).longValue();
                jdbc.update("UPDATE users SET api_key_cipher=NULL, api_key_nonce=NULL, " +
                        "api_key_version=0, api_key_last4=NULL WHERE id=?", id);
                cleared++;
                log.warn("用户 {} 的 API Key 无法用当前主密钥解密（已清空，需重新填写）", id);
            }
        }
        if (cleared > 0) {
            log.warn("共清空 {} 个无法解密的 API Key；请通知用户到个人中心重新填写", cleared);
        }
    }
}
