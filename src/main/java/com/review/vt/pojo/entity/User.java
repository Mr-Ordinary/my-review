package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

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
@TableName("users")
@Getter
@Setter
public class User {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户名
    private String username;

    // 密码哈希
    private String passwordHash;

    // 昵称
    private String nickname;

    // 头像扩展名
    private String avatarExt;

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

    // 求职总结
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String summaryJson;

    // 总结时间
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long summaryAt;

    // 总结状态
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String summaryStatus;

    // 总结失败原因
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String summaryError;

    // 总结开始时间
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long summaryStartedAt;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

}
