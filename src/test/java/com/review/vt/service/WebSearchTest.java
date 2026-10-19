package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 联网搜索：出题前用来校准「这题现在还常问吗」。搜不到要降级，不能影响出题。 */
class WebSearchTest {

    private static final WebSearch S = new WebSearch();

    private static final String HTML = """
        <html><body><ol>
          <li class="b_algo"><h2><a href="/a">Java 并发：线程池参数怎么配</a></h2>
            <div class="b_caption"><p>核心线程、最大线程、队列与拒绝策略的搭配，2025 年仍高频。</p></div></li>
          <li class="b_algo"><h2><a href="/b">HashMap 扩容与树化</a></h2>
            <div class="b_caption"><p>容量、负载因子、高低位拆分。</p></div></li>
          <li class="b_algo"><h2><a href="/c">某个广告</a></h2></li>
        </ol></body></html>
        """;

    @Test
    @DisplayName("从搜索结果页抽出标题与摘要")
    void parse_extractsHits() {
        List<WebSearch.Hit> hits = S.parse(HTML);
        assertEquals(3, hits.size());
        assertEquals("Java 并发：线程池参数怎么配", hits.get(0).title());
        assertTrue(hits.get(0).snippet().contains("高频"));
    }

    @Test
    @DisplayName("线索数量受限，避免塞满上下文")
    void parse_capsHits() {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 0; i < 20; i++) {
            sb.append("<li class=\"b_algo\"><h2>标题").append(i).append("</h2><p>摘要</p></li>");
        }
        sb.append("</body></html>");
        assertTrue(S.parse(sb.toString()).size() <= WebSearch.MAX_HITS);
    }

    @Test
    @DisplayName("页面结构不对时返回空列表，不抛异常")
    void parse_badHtml() {
        assertTrue(S.parse("<html><body><p>没有任何搜索结果</p></body></html>").isEmpty());
        assertTrue(S.parse(null).isEmpty());
        assertTrue(S.parse("").isEmpty());
    }

    @Test
    @DisplayName("搜索失败降级为空列表（不影响出题）")
    void search_degradesGracefully() {
        assertTrue(S.search(null).isEmpty());
        assertTrue(S.search("   ").isEmpty());
    }

    @Test
    @DisplayName("拼给 AI 的线索段落：有就带上，没有就是空串")
    void toPrompt_rendering() {
        assertTrue(WebSearch.toPrompt(List.of()).isBlank());
        assertTrue(WebSearch.toPrompt(null).isBlank());
        String out = WebSearch.toPrompt(S.parse(HTML));
        assertTrue(out.contains("近期高频面经线索"));
        assertTrue(out.contains("线程池参数"));
    }

    @Test
    @DisplayName("容量上限是明确的常量，不会悄悄变")
    void limits_arePinned() {
        assertEquals(50, Limits.MAX_PRACTICES_PER_STAGE);
        assertEquals(20, Limits.MAX_PAPERS_PER_PRACTICE);
    }
}
