package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网页正文抽取。用户实际是在网上看复习资料的，所以「参照 xxx 链接」必须真能读到内容。
 * 这里覆盖 URL 提取与 HTML → 正文，避免抓下来一堆菜单页脚。
 */
class WebFetcherTest {

    private static final WebFetcher F = new WebFetcher();

    @Test
    @DisplayName("从中文语境里抽出 URL，中文标点后面正确断开")
    void extractUrls_fromChinese() {
        Set<String> urls = WebFetcher.extractUrls(
                "范围是基础篇，参照 https://javaguide.cn/java/collection/，另外还有 https://example.com/a.html。");
        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://javaguide.cn/java/collection/"));
        assertTrue(urls.contains("https://example.com/a.html"));
    }

    @Test
    @DisplayName("去掉链接尾部的标点")
    void extractUrls_stripsTrailingPunctuation() {
        Set<String> urls = WebFetcher.extractUrls("见 https://example.com/page，注意");
        assertEquals(1, urls.size());
        assertFalse(urls.iterator().next().endsWith("，"));
    }

    @Test
    @DisplayName("去重并保持顺序")
    void extractUrls_dedupes() {
        Set<String> urls = WebFetcher.extractUrls("https://a.com/1 https://a.com/1 https://b.com/2");
        assertEquals(List.of("https://a.com/1", "https://b.com/2"), List.copyOf(urls));
    }

    @Test
    @DisplayName("没有链接时返回空")
    void extractUrls_none() {
        assertTrue(WebFetcher.extractUrls("只是一段文字").isEmpty());
        assertTrue(WebFetcher.extractUrls(null).isEmpty());
    }

    @Test
    @DisplayName("抽出正文，去掉脚本/样式/导航/页脚这些噪音")
    void parse_extractsMainText() {
        String html = """
            <html><head><title>HashMap 详解</title>
            <style>body{color:red}</style><script>alert(1)</script></head>
            <body>
              <nav>首页 / 目录 / 关于我们</nav>
              <main>
                <h1>HashMap 详解</h1>
                <p>HashMap 基于数组 + 链表 + 红黑树实现。</p>
                <p>扩容时容量翻倍，并重新计算下标。</p>
              </main>
              <footer>版权所有 2024</footer>
            </body></html>
            """;
        WebFetcher.FetchResult r = F.parse("https://example.com/hm", html);
        assertTrue(r.ok());
        assertEquals("HashMap 详解", r.title());
        assertTrue(r.text().contains("数组 + 链表 + 红黑树"));
        assertTrue(r.text().contains("扩容"));
        assertFalse(r.text().contains("alert"), "脚本内容不能进正文");
        assertFalse(r.text().contains("版权所有"), "页脚不能进正文");
        assertFalse(r.text().contains("首页 / 目录"), "导航不能进正文");
    }

    @Test
    @DisplayName("纯前端渲染的空壳页面：明确报错，而不是把空正文当成功")
    void parse_emptyShellReportsError() {
        String html = "<html><head><title>SPA</title></head><body><div id='root'></div><script>app()</script></body></html>";
        WebFetcher.FetchResult r = F.parse("https://example.com/spa", html);
        assertFalse(r.ok());
        assertTrue(r.error().contains("没有可读正文"), "要明确告诉用户读不到：" + r.error());
    }

    @Test
    @DisplayName("正文超长要截断，避免撑爆上下文")
    void parse_truncatesLongText() {
        String body = "这是一段很长的正文。".repeat(2000);
        String html = "<html><body><main><p>" + body + "</p></main></body></html>";
        WebFetcher.FetchResult r = F.parse("https://example.com/long", html);
        assertTrue(r.ok());
        assertTrue(r.text().length() <= WebFetcher.MAX_TEXT + 1, "实际 " + r.text().length());
    }

    @Test
    @DisplayName("正文清洗：压掉多余空白但保留段落")
    void cleanText_normalizesWhitespace() {
        String s = WebFetcher.cleanText("  第一段   \n\n\n\n  第二段  \t\t  ");
        assertTrue(s.contains("第一段"));
        assertTrue(s.contains("第二段"));
        assertFalse(s.contains("\n\n\n"), "连续空行要压掉");
    }
}
