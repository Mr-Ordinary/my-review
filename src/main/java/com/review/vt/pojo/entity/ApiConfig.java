package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 一套 LLM/API 配置：一个用户可有多套（各带独立密钥/地址/模型），可指定启用哪套，
 * 也可共享给其他用户使用（见 {@link ApiShare}）。
 * apiKey 用 AES-256-GCM 加密后落库（cipher + nonce + version），明文只在快照生成瞬间解密。
 */
@TableName("llm_config")
@Getter
@Setter
public class ApiConfig {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;
    // 用户ID
    private Long userId;
    // 配置名
    private String name;
    // API Key 密文
    private String apiKeyCipher;
    // API Key 随机数
    private String apiKeyNonce;
    // 加密版本
    private int apiKeyVersion;
    // API Key 末四位
    private String apiKeyLast4;
    // 接口地址
    private String apiBase;
    // 模型
    private String model;
    // 接口协议
    private String apiFormat;
    // 温度
    private Double temperature;
    // 创建时间
    private long createdAt;
    // 更新时间
    private long updatedAt;
}
