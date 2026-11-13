package com.review.vt.service;

import com.review.vt.auth.AuthContext;
import com.review.vt.domain.ApiConfig;
import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import com.review.vt.security.AesGcmCrypto;
import com.review.vt.security.MasterKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 按用户的 LLM 配置解析。优先取**当前启用的配置**（llm_config，自己的或共享来的），
 * 未启用时回落 users 表旧字段（迁移前的老数据），再回落服务端默认（application.yml）。
 *
 * <p>快照（{@link LlmConfig}）在请求线程/后台任务线程间显式传递，
 * 明文 key 只活在堆上，绝不落库、绝不出后端、绝不进日志。
 */
@Service
public class UserLlmService {

    private final UserRepository users;
    private final ApiConfigService apiConfigs;
    private final AesGcmCrypto crypto;
    private final String defaultApiBase;
    private final String defaultApiKey;
    private final String defaultModel;
    private final String defaultApiFormat;
    private final double defaultTemperature;

    public UserLlmService(UserRepository users, ApiConfigService apiConfigs, MasterKeyService masterKey,
                          @Value("${myreview.llm.api-base}") String apiBase,
                          @Value("${myreview.llm.api-key:}") String apiKey,
                          @Value("${myreview.llm.model}") String model,
                          @Value("${myreview.llm.api-format:anthropic}") String apiFormat,
                          @Value("${myreview.llm.temperature}") double temperature) {
        this.users = users;
        this.apiConfigs = apiConfigs;
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
        ApiConfig cfg = apiConfigs.activeConfigFor(userId).orElse(null);
        if (cfg != null) {
            String apiKey = apiConfigs.decryptKey(cfg);
            if (apiKey.isBlank()) apiKey = defaultApiKey;
            return new LlmConfig(
                    orDefault(cfg.getApiBase(), defaultApiBase),
                    apiKey,
                    orDefault(cfg.getModel(), defaultModel),
                    orDefault(cfg.getApiFormat(), defaultApiFormat),
                    cfg.getTemperature() != null ? clamp(cfg.getTemperature()) : defaultTemperature);
        }
        // 未启用配置：users 表旧字段（迁移前老数据兜底），再回落服务端默认
        String apiKey = decryptLegacyKey(u);
        if (apiKey.isBlank()) apiKey = defaultApiKey;
        return new LlmConfig(
                orDefault(u.getApiBase(), defaultApiBase),
                apiKey,
                orDefault(u.getModel(), defaultModel),
                orDefault(u.getApiFormat(), defaultApiFormat),
                u.getTemperature() != null ? clamp(u.getTemperature()) : defaultTemperature);
    }

    /** 当前登录用户的一次性快照（同步调用路径用） */
    public LlmConfig snapshotForCurrent() {
        return snapshotFor(AuthContext.require().userId());
    }

    /** 解密 users 表旧字段里的 API Key（迁移前老数据）；未配置返回空串 */
    private String decryptLegacyKey(User u) {
        if (u.getApiKeyCipher() == null || u.getApiKeyCipher().isBlank()
                || u.getApiKeyNonce() == null || u.getApiKeyNonce().isBlank()) {
            return "";
        }
        return crypto.decrypt(u.getApiKeyNonce(), u.getApiKeyCipher());
    }

    /** 掩码回显：sk-****xxxx（只露末 4 位，够用户辨认自己填的是哪把 key） */
    public static String mask(String last4) {
        return last4 == null || last4.isBlank() ? null : "sk-****" + last4;
    }

    private static String orDefault(String v, String def) {
        return v != null && !v.isBlank() ? v : def;
    }

    private static double clamp(double t) {
        if (t < 0) return 0.0;
        if (t > 1) return 1.0;
        return t;
    }
}
