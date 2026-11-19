package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 网上资料的抓取缓存：范围模式里提到的 URL 会被真的抓下来读正文。
 * 抓一次就存住，同一份资料不必反复抓（很多文档站会限流）。
 * 字段与 data/store/sources.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("source")
@Getter
@Setter
public class Source {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 网页地址
    private String url;

    // 标题
    private String title;

    // 正文
    private String text;

    // 抓取时间
    private long fetchedAt;

    // 抓取失败原因
    private String error;

}
