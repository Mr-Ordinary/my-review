package com.review.vt.domain;

/**
 * 一套 LLM/API 配置：一个用户可有多套（各带独立密钥/地址/模型），可指定启用哪套，
 * 也可共享给其他用户使用（见 {@link ApiShare}）。
 * apiKey 用 AES-256-GCM 加密后落库（cipher + nonce + version），明文只在快照生成瞬间解密。
 */
public class ApiConfig {

    private Long id;
    private Long userId;
    private String name;
    private String apiKeyCipher;
    private String apiKeyNonce;
    private int apiKeyVersion;
    private String apiKeyLast4;
    private String apiBase;
    private String model;
    private String apiFormat;
    private Double temperature;
    private long createdAt;
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getApiKeyCipher() { return apiKeyCipher; }
    public void setApiKeyCipher(String apiKeyCipher) { this.apiKeyCipher = apiKeyCipher; }

    public String getApiKeyNonce() { return apiKeyNonce; }
    public void setApiKeyNonce(String apiKeyNonce) { this.apiKeyNonce = apiKeyNonce; }

    public int getApiKeyVersion() { return apiKeyVersion; }
    public void setApiKeyVersion(int apiKeyVersion) { this.apiKeyVersion = apiKeyVersion; }

    public String getApiKeyLast4() { return apiKeyLast4; }
    public void setApiKeyLast4(String apiKeyLast4) { this.apiKeyLast4 = apiKeyLast4; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiFormat() { return apiFormat; }
    public void setApiFormat(String apiFormat) { this.apiFormat = apiFormat; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
