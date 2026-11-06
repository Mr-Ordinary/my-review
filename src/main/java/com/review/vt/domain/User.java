package com.review.vt.domain;

/**
 * 用户：登录账号 + 个人资料 + 加密的 API Key + 个人求职总结。
 *
 * <p>安全设计：
 * - passwordHash 用 Argon2id（spring-security-crypto），禁止可逆存储；
 * - apiKey 用 AES-256-GCM 加密后落库（cipher + nonce + version），
 *   主密钥在服务端文件（myreview.master-key-file），不入库、不入仓库；
 *   apiKeyLast4 只用于前端掩码回显（sk-****xxxx）。
 *
 * 字段与 users 表列 1:1 对应。
 */
public class User {

    private Long id;

    /** 登录名，全局唯一 */
    private String username;

    /** Argon2id 哈希（带盐、带参数，自校验） */
    private String passwordHash;

    /** 昵称（个人中心可改） */
    private String nickname;

    /** 头像文件扩展名（png/jpg/…），文件存 {data-dir}/avatars/{id}.{ext}；null=默认头像 */
    private String avatarExt;

    /** API Key 密文（AES-256-GCM，Base64） */
    private String apiKeyCipher;

    /** 加密用随机 nonce（Base64） */
    private String apiKeyNonce;

    /** 密钥版本，预留主密钥轮换 */
    private int apiKeyVersion;

    /** API Key 末 4 位，掩码回显用 */
    private String apiKeyLast4;

    /** API Base URL（用户自定义，null=用服务端默认） */
    private String apiBase;

    /** 模型名（用户自定义，null=用服务端默认） */
    private String model;

    /** 接口协议 anthropic / openai（用户自定义，null=用服务端默认） */
    private String apiFormat;

    /** 温度（用户自定义，null=用服务端默认） */
    private Double temperature;

    /** 个人求职总结 JSON（feature 11），生成后缓存展示 */
    private String summaryJson;

    /** 求职总结生成时间 */
    private Long summaryAt;

    /** 求职总结任务状态：idle / generating / failed（持久化，刷新页面后仍可知道是否在生成） */
    private String summaryStatus;

    /** 最近一次生成失败的原因（status=failed 时有值） */
    private String summaryError;

    /** 本次生成任务开始时间；服务重启后据此识别并回收卡死的 generating 状态 */
    private Long summaryStartedAt;

    private long createdAt;

    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarExt() { return avatarExt; }
    public void setAvatarExt(String avatarExt) { this.avatarExt = avatarExt; }
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
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Long getSummaryAt() { return summaryAt; }
    public void setSummaryAt(Long summaryAt) { this.summaryAt = summaryAt; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
    public String getSummaryError() { return summaryError; }
    public void setSummaryError(String summaryError) { this.summaryError = summaryError; }
    public Long getSummaryStartedAt() { return summaryStartedAt; }
    public void setSummaryStartedAt(Long summaryStartedAt) { this.summaryStartedAt = summaryStartedAt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
