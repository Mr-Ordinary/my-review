package com.review.vt.domain;

/**
 * 网上资料的抓取缓存：范围模式里提到的 URL 会被真的抓下来读正文。
 * 抓一次就存住，同一份资料不必反复抓（很多文档站会限流）。
 * 字段与 data/store/sources.json 行（及后续数据库表列）1:1 对应。
 */
public class Source {

    /** 就用 URL 本身当 id，天然去重 */
    private String id;

    private String url;

    private String title;

    /** 抽出来的正文纯文本（已截断） */
    private String text;

    private long fetchedAt;

    /** 抓取失败的原因；成功为 null */
    private String error;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public long getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(long fetchedAt) { this.fetchedAt = fetchedAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
