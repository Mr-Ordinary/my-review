package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 笔记正文与场景的纯校验。
 * 前端 maxlength 只防误操作，服务端这两道闸才是底线。
 */
class NoteServiceTest {

    @Test
    @DisplayName("正文 trim 后非空、不超长才通过")
    void normalizeText_ok() {
        assertEquals("记一笔", NoteService.normalizeText("  记一笔  "));
        String max = "x".repeat(Limits.MAX_NOTE_TEXT);
        assertEquals(max, NoteService.normalizeText(max), "刚好到上限要放行");
    }

    @Test
    @DisplayName("空笔记与超长笔记直接拒绝")
    void normalizeText_rejectsBlankAndTooLong() {
        assertThrows(IllegalArgumentException.class, () -> NoteService.normalizeText(null));
        assertThrows(IllegalArgumentException.class, () -> NoteService.normalizeText("   "));
        String over = "x".repeat(Limits.MAX_NOTE_TEXT + 1);
        assertThrows(IllegalArgumentException.class, () -> NoteService.normalizeText(over));
    }

    @Test
    @DisplayName("场景白名单：空归 manual，非法值拒绝")
    void requireScene_whitelist() {
        assertEquals("manual", NoteService.requireScene(null));
        assertEquals("manual", NoteService.requireScene("  "));
        assertEquals("answering", NoteService.requireScene("answering"));
        assertEquals("grading", NoteService.requireScene("grading"));
        assertEquals("manual", NoteService.requireScene("manual"));
        assertThrows(IllegalArgumentException.class, () -> NoteService.requireScene("hacking"));
    }
}
