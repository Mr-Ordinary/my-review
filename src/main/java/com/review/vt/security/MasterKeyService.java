package com.review.vt.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 加密主密钥：API Key 落库加密用的 AES-256 密钥。
 *
 * <p>来源优先级：
 * <ol>
 *   <li>环境变量 {@code MYREVIEW_MASTER_KEY}（Base64，32 字节）——部署/测试用</li>
 *   <li>文件 {@code myreview.master-key-file}（默认 E:/m_review/master.key）——
 *       首次启动自动生成 32 随机字节写入，之后直接读</li>
 * </ol>
 *
 * <p><b>这个文件绝不上库</b>（.gitignore 已含 data/ 与 *.key）。威胁模型：拿到数据库
 * 但拿不到主密钥文件 → API Key 仍是密文；拿到服务器本体 → 一切皆输（本就如此）。
 */
@Service
public class MasterKeyService {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyService.class);

    private final Path keyFile;
    private final String envKey;
    private SecretKey key;

    public MasterKeyService(@Value("${myreview.master-key-file:E:/m_review/master.key}") String keyFile,
                            @Value("${MYREVIEW_MASTER_KEY:}") String envKey) {
        this.keyFile = Path.of(keyFile);
        this.envKey = envKey;
    }

    @PostConstruct
    void load() {
        try {
            if (envKey != null && !envKey.isBlank()) {
                key = keyOf(Base64.getDecoder().decode(envKey.trim()));
                log.info("主密钥来自环境变量 MYREVIEW_MASTER_KEY");
                return;
            }
            if (Files.exists(keyFile)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile).trim());
                key = keyOf(raw);
                log.info("主密钥已从 {} 加载", keyFile);
                return;
            }
            // 首次启动：生成并落盘
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw));
            key = keyOf(raw);
            log.warn("已生成新的加密主密钥：{}（丢失该文件将无法解密已保存的 API Key，只能重新填写）", keyFile);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("加密主密钥加载失败（" + keyFile + "）：" + e.getMessage(), e);
        }
    }

    public SecretKey key() {
        return key;
    }

    private static SecretKey keyOf(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalArgumentException("主密钥必须是 32 字节（Base64 前 44 字符）");
        }
        return new SecretKeySpec(raw, "AES");
    }
}
