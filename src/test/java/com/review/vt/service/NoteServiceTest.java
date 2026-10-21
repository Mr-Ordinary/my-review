package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记事本是一整块可编辑文本，不是「一条一条」的记录。
 * 所以这里卡的是：清空合法、超长拒绝、追加只在末尾换行、行数怎么数。
 */
class NoteServiceTest {

    @Test
    @DisplayName("正文只挡超长；空文本合法（等于清空记事本）")
    void normalizeText_allowsEmptyButNotTooLong() {
        assertEquals("  记一笔  ", NoteService.normalizeText("  记一笔  "),
                "原样保留、不做 trim——记事本的空白由用户自己排版");
        assertEquals("", NoteService.normalizeText(null), "空 = 清空，合法");
        assertEquals("", NoteService.normalizeText(""), "空 = 清空，合法");

        String max = "x".repeat(Limits.MAX_NOTE_TEXT);
        assertEquals(max, NoteService.normalizeText(max), "刚好到上限要放行");
        String over = "x".repeat(Limits.MAX_NOTE_TEXT + 1);
        assertThrows(IllegalArgumentException.class, () -> NoteService.normalizeText(over), "超长拒绝");
    }

    @Test
    @DisplayName("追加：末尾换行后接新内容，空追加不堆空行")
    void appendTo_addsLineAtEnd() {
        assertEquals("第一行", NoteService.appendTo(null, "第一行"), "空记事本直接写入");
        assertEquals("第一行", NoteService.appendTo("", "  第一行  "), "追加内容要 trim");
        assertEquals("第一行\n第二行", NoteService.appendTo("第一行", "第二行"), "末尾换行追加");
        assertEquals("第一行\n第二行", NoteService.appendTo("第一行\n", "第二行"), "已经以换行结尾就别再加一个");
        assertEquals("第一行", NoteService.appendTo("第一行", "   "), "空白追加忽略，不堆空行");
        assertEquals("第一行", NoteService.appendTo("第一行", null), "null 追加忽略");
    }

    @Test
    @DisplayName("行数只数非空行，给练习列表显示「几条」用")
    void countLines_skipsBlank() {
        assertEquals(0, NoteService.countLines(null));
        assertEquals(0, NoteService.countLines(""));
        assertEquals(0, NoteService.countLines("   \n  \n"), "全是空行算 0");
        assertEquals(3, NoteService.countLines("一\n\n二\n三\n"), "中间的空行不算");
    }
}
