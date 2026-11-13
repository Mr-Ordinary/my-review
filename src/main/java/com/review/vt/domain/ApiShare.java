package com.review.vt.domain;

/**
 * API 配置共享：所有者把自己的配置共享给目标用户使用。
 * 目标用户可在配置列表里选用（标注来源），但不能编辑/删除；所有者可随时取消。
 */
public class ApiShare {

    private Long id;
    private Long configId;
    private Long ownerUserId;
    private Long targetUserId;
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
