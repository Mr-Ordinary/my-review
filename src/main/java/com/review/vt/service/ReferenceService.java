package com.review.vt.service;

import com.review.vt.domain.Source;
import com.review.vt.repo.SourceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把范围描述里提到的 URL 变成真正可读的参考资料。
 *
 * 用户实际是在网上看复习资料的，所以「参照 xxx 链接」必须落到实处：
 * 读不到就明确告诉他读不到、请粘贴正文，而不是装作读过。
 */
@Service
public class ReferenceService {

    /** 整段参考资料最多多少字（受 PromptBudget 约束，这里再收一道） */
    static final int MAX_TOTAL = 6000;

    private final SourceRepository sources;
    private final WebFetcher fetcher;

    public ReferenceService(SourceRepository sources, WebFetcher fetcher) {
        this.sources = sources;
        this.fetcher = fetcher;
    }

    /**
     * 展开范围文本里的 URL，返回「网上资料」段落（给 AI 当出题依据）。
     * 没有 URL 时返回空串；抓不到的链接会在段落里如实说明，方便用户补粘。
     */
    public String expand(String scopeText) {
        List<WebFetcher.FetchResult> results = resolve(scopeText);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("网上资料（已抓取正文，出题以这些内容为准）：\n");
        int used = 0;
        for (WebFetcher.FetchResult r : results) {
            if (used >= MAX_TOTAL) {
                sb.append("（资料较多，后面的内容已省略）\n");
                break;
            }
            sb.append("【").append(r.title() == null || r.title().isBlank() ? r.url() : r.title()).append("】\n");
            sb.append("来源：").append(r.url()).append('\n');
            if (r.ok()) {
                String body = MemoryRules.cut(r.text(), MAX_TOTAL - used);
                sb.append(body).append('\n');
                used += body.length();
            } else {
                sb.append("（读取失败：").append(r.error()).append("。请让用户把正文粘进范围描述）\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析并抓取范围文本里的链接（命中缓存就不重抓）。
     * 最多取 {@link WebFetcher#MAX_URLS_PER_SCOPE} 个，避免一段描述塞满链接把上下文撑爆。
     */
    public List<WebFetcher.FetchResult> resolve(String scopeText) {
        Set<String> urls = WebFetcher.extractUrls(scopeText);
        List<WebFetcher.FetchResult> out = new ArrayList<>();
        int n = 0;
        for (String url : urls) {
            if (n++ >= WebFetcher.MAX_URLS_PER_SCOPE) break;
            out.add(resolveOne(url));
        }
        return out;
    }

    /** 单个链接：先查缓存，没有就抓一次并存住（失败也存，免得反复重试同一个坏链接） */
    public WebFetcher.FetchResult resolveOne(String url) {
        Source cached = sources.findById(url).orElse(null);
        if (cached != null) {
            boolean ok = cached.getError() == null || cached.getError().isBlank();
            return new WebFetcher.FetchResult(cached.getUrl(), cached.getTitle(), cached.getText(),
                    ok ? null : cached.getError());
        }

        WebFetcher.FetchResult r = fetcher.fetch(url);
        Source s = new Source();
        s.setId(url);
        s.setUrl(url);
        s.setTitle(r.title());
        s.setText(r.text());
        s.setError(r.ok() ? null : r.error());
        s.setFetchedAt(System.currentTimeMillis());
        sources.save(s);
        return r;
    }
}
