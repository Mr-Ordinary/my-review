package com.review.vt.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抓取网上资料的正文。
 *
 * 用户实际是在网上看复习资料的，所以范围模式里提到的 URL 必须**真的能读到内容**，
 * 不能只当一句空话传给 AI。这里做 HTML → 纯文本的抽取。
 *
 * 已知边界（调用方要把这些如实告诉用户）：
 * - 纯前端渲染（SPA）的站点，HTML 里没有正文，抓出来是空的
 * - 需要登录 / 有反爬的站点会 403、401 或返回验证页
 * - PDF、图片、视频等非 HTML 资源读不了
 */
@Service
public class WebFetcher {

    /** 从一段文字里抽出 http(s) 链接（中文标点后也常见，要一起断开） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s<>\"'，。；、：！？（）()【】\\[\\]]+");

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 单个 URL 的正文最多取多少字 */
    static final int MAX_TEXT = 6000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(HttpProxies.selector())
            .build();

    /** 抓取结果：error 非空表示失败，失败也要记住，免得每次都重试 */
    public record FetchResult(String url, String title, String text, String error) {
        public boolean ok() {
            return error == null || error.isBlank();
        }
    }

    /** 从文本里抽出全部 URL（去重、保序） */
    public static Set<String> extractUrls(String text) {
        Set<String> urls = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return urls;
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String u = m.group().replaceAll("[.,;:!?]+$", "");
            if (u.length() > 12) urls.add(u);
        }
        return urls;
    }

    public FetchResult fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                return new FetchResult(url, "", "", "HTTP " + code + "（可能是登录墙、反爬或链接已失效）");
            }
            String ctype = resp.headers().firstValue("content-type").orElse("");
            if (!ctype.isEmpty() && !ctype.toLowerCase().contains("html") && !ctype.toLowerCase().contains("xml")) {
                return new FetchResult(url, "", "", "不是网页（" + ctype + "），这种格式读不了，请把内容粘进范围描述");
            }

            String html = resp.body();
            if (html == null || html.isBlank()) {
                return new FetchResult(url, "", "", "页面内容为空");
            }
            return parse(url, html);
        } catch (IllegalArgumentException e) {
            return new FetchResult(url, "", "", "链接格式不正确：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(url, "", "", "抓取被中断");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new FetchResult(url, "", "", "抓取失败：" + m);
        }
    }

    /** HTML → 标题 + 正文纯文本 */
    FetchResult parse(String url, String html) {
        Document doc = Jsoup.parse(html, url);
        String title = doc.title();

        // 去掉导航、脚本、样式这些噪音，否则正文会被菜单和页脚糊满
        doc.select("script,noscript,style,svg,iframe,nav,footer,header,aside,form,button,input,select,textarea").remove();
        doc.select("[role=navigation],[role=banner],[role=contentinfo],.sidebar,.toc,.breadcrumb,.nav,.menu,.footer,.header,.comment,.advertisement,.ads").remove();

        Element main = doc.selectFirst("main, article, [role=main], .markdown-body, .post-content, .article-content, .content, #content");
        String text = (main == null ? doc.body() : main).text();

        text = cleanText(text);
        if (text.isBlank()) {
            // 典型的纯前端渲染页面：HTML 里根本没有正文
            return new FetchResult(url, title, "", "网页里没有可读正文（很可能是纯前端渲染的站点）。请把内容复制粘贴进范围描述");
        }
        return new FetchResult(url, title, cut(text, MAX_TEXT), null);
    }

    /** 压掉多余空白，保留段落感 */
    static String cleanText(String raw) {
        if (raw == null) return "";
        String s = raw.replace(' ', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" {2,}", " ");
        return s.trim();
    }

    private static String cut(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    /** 供调用方判断链接数量，避免一段描述里塞十几个链接把上下文撑爆 */
    public static final int MAX_URLS_PER_SCOPE = 3;
}
