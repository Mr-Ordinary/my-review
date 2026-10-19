package com.review.vt.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 出题前的时效性核对：搜一下当前的高频面经线索，喂给出题提示词，
 * 避免出那些已经被淘汰、面试官早就不问的老题。
 *
 * 这里是**真的联网搜索**（抓搜索引擎结果页），不是让模型"假装联网"。
 * 但它只是「线索」——出题仍以模型知识为准，搜索结果用来校准时效性和高频度。
 *
 * 已知边界：搜索引擎可能限流或改版；拿不到就降级为不给线索，不影响出题。
 */
@Service
public class WebSearch {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 一次搜索最多取几条线索 */
    static final int MAX_HITS = 6;

    /** 搜索必须快速失败：它是出题的「加分项」，不能拖慢出题主流程 */
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int REQUEST_TIMEOUT_SEC = 8;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 同一个查询的结果缓存（含失败），避免每次出题都去搜一遍 */
    private final Map<String, List<Hit>> cache = new ConcurrentHashMap<>();
    private static final List<Hit> EMPTY = List.of();

    /** 一条搜索线索 */
    public record Hit(String title, String snippet) {}

    /** 搜一批面经线索；失败返回空列表（降级，不影响出题） */
    public List<Hit> search(String query) {
        if (query == null || query.isBlank()) return EMPTY;
        String key = query.trim();
        List<Hit> cached = cache.get(key);
        if (cached != null) return cached;

        List<Hit> hits = EMPTY;
        try {
            String url = "https://www.bing.com/search?setlang=zh-CN&count=10&q="
                    + URLEncoder.encode(key, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                    .header("User-Agent", UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) hits = parse(resp.body());
        } catch (Exception e) {
            System.err.println("[myreview] 联网搜索失败（降级为不出时效线索）：" + e.getMessage());
        }
        cache.put(key, hits);
        return hits;
    }

    /** 从搜索结果页里抽标题 + 摘要 */
    List<Hit> parse(String html) {
        List<Hit> hits = new ArrayList<>();
        if (html == null || html.isBlank()) return hits;
        Document doc = Jsoup.parse(html);
        Elements blocks = doc.select("li.b_algo");
        for (Element b : blocks) {
            if (hits.size() >= MAX_HITS) break;
            String title = b.selectFirst("h2") == null ? "" : b.selectFirst("h2").text().trim();
            Element cap = b.selectFirst(".b_caption p, p");
            String snippet = cap == null ? "" : cap.text().trim();
            if (title.isEmpty()) continue;
            hits.add(new Hit(MemoryRules.cut(title, 60), MemoryRules.cut(snippet, 120)));
        }
        return hits;
    }

    /** 拼成给 AI 的「近期高频面经线索」段落 */
    public static String toPrompt(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("近期高频面经线索（联网搜索所得，用来校准时效性，避免出过时的题）：\n");
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            sb.append(i + 1).append(". ").append(h.title());
            if (!h.snippet().isBlank()) sb.append(" —— ").append(h.snippet());
            sb.append('\n');
        }
        return sb.toString();
    }
}
