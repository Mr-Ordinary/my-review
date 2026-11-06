package com.review.vt.service;

import com.review.vt.auth.AuthContext;
import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import com.review.vt.security.AesGcmCrypto;
import com.review.vt.security.MasterKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 按用户的 LLM 配置解析。每项配置优先取用户自己的值（users 表），
 * 没配则回落服务端默认（application.yml）。apiKey 是密文存 users 表，
 * 解密只发生在快照生成的瞬间。
 *
 * <p>快照（{@link LlmConfig}）在请求线程/后台任务线程间显式传递，
 * 明文 key 只活在堆上，绝不落库、绝不出后端、绝不进日志。
 */
@Service
public class UserLlmService {

    private final UserRepository users;
    private final AesGcmCrypto crypto;
    private final String defaultApiBase;
    private final String defaultApiKey;
    private final String defaultModel;
    private final String defaultApiFormat;
    private final double defaultTemperature;

    public UserLlmService(UserRepository users, MasterKeyService masterKey,
                          @Value("${myreview.llm.api-base}") String apiBase,
                          @Value("${myreview.llm.api-key:}") String apiKey,
                          @Value("${myreview.llm.model}") String model,
                          @Value("${myreview.llm.api-format:anthropic}") String apiFormat,
                          @Value("${myreview.llm.temperature}") double temperature) {
        this.users = users;
        this.crypto = new AesGcmCrypto(masterKey.key());
        this.defaultApiBase = apiBase;
        this.defaultApiKey = apiKey;
        this.defaultModel = model;
        this.defaultApiFormat = apiFormat;
        this.defaultTemperature = temperature;
    }

    /** 指定用户的一次性 LLM 配置快照（后台任务、续跑路径用） */
    public LlmConfig snapshotFor(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        String apiKey = decryptKey(u);
        if (apiKey.isBlank()) apiKey = defaultApiKey;
        String apiBase = (u.getApiBase() != null && !u.getApiBase().isBlank()) ? u.getApiBase() : defaultApiBase;
        String model = (u.getModel() != null && !u.getModel().isBlank()) ? u.getModel() : defaultModel;
        String apiFormat = (u.getApiFormat() != null && !u.getApiFormat().isBlank()) ? u.getApiFormat() : defaultApiFormat;
        double temperature = u.getTemperature() != null ? u.getTemperature() : defaultTemperature;
        return new LlmConfig(apiBase, apiKey, model, apiFormat, clamp(temperature));
    }

    /** 当前登录用户的一次性快照（同步调用路径用） */
    public LlmConfig snapshotForCurrent() {
        return snapshotFor(AuthContext.require().userId());
    }

    /** 解密用户的 API Key；未配置返回空串 */
    public String decryptKey(User u) {
        if (u.getApiKeyCipher() == null || u.getApiKeyCipher().isBlank()
                || u.getApiKeyNonce() == null || u.getApiKeyNonce().isBlank()) {
            return "";
        }
        return crypto.decrypt(u.getApiKeyNonce(), u.getApiKeyCipher());
    }

    /**
     * 设置/清除当前用户自己的 API Key。传入空串 = 清除。
     * 前端提交的 apiKey 若含 '*'（掩码回显回写）视为未修改，不动密文。
     */
    public User setApiKey(Long userId, String plainKey) {
        User u = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        if (plainKey == null || plainKey.isBlank()) {
            u.setApiKeyCipher(null);
            u.setApiKeyNonce(null);
            u.setApiKeyVersion(0);
            u.setApiKeyLast4(null);
        } else {
            String trimmed = plainKey.trim();
            AesGcmCrypto.Encrypted enc = crypto.encrypt(trimmed);
            u.setApiKeyCipher(enc.cipherText());
            u.setApiKeyNonce(enc.nonce());
            u.setApiKeyVersion(AesGcmCrypto.VERSION);
            u.setApiKeyLast4(last4(trimmed));
        }
        u.setUpdatedAt(System.currentTimeMillis());
        users.update(u);
        return u;
    }

    /**
     * 更新用户的 LLM 配置（apiBase / model / apiFormat / temperature）。
     * 传 null 或空串 = 清除自定义，回落服务端默认。
     */
    public User setLlmSettings(Long userId, String apiBase, String model, String apiFormat, Double temperature) {
        User u = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        u.setApiBase(apiBase == null || apiBase.isBlank() ? null : apiBase.trim());
        u.setModel(model == null || model.isBlank() ? null : model.trim());
        if (apiFormat != null && !apiFormat.isBlank()) {
            String v = apiFormat.trim().toLowerCase();
            if (!v.equals("anthropic") && !v.equals("openai")) {
                throw new IllegalArgumentException("接口协议只支持 anthropic 或 openai");
            }
            u.setApiFormat(v);
        } else {
            u.setApiFormat(null);
        }
        u.setTemperature(temperature != null ? clamp(temperature) : null);
        u.setUpdatedAt(System.currentTimeMillis());
        users.update(u);
        return u;
    }

    /** 当前用户的 LLM 配置回显（不含 apiKey 明文） */
    public java.util.Map<String, Object> llmSettingsOf(Long userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
        LlmConfig cfg = snapshotFor(userId);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("apiBase", cfg.apiBase());
        m.put("model", cfg.model());
        m.put("apiFormat", cfg.apiFormat());
        m.put("temperature", cfg.temperature());
        boolean hasKey = u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank();
        m.put("hasApiKey", hasKey);
        m.put("masked", mask(u.getApiKeyLast4()));
        return m;
    }

    /** 掩码回显：sk-****xxxx（只露末 4 位，够用户辨认自己填的是哪把 key） */
    public static String mask(String last4) {
        return last4 == null || last4.isBlank() ? null : "sk-****" + last4;
    }

    private static String last4(String key) {
        return key.length() <= 4 ? key : key.substring(key.length() - 4);
    }

    private static double clamp(double t) {
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t;
    }
}
