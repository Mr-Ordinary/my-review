package com.review.vt.service;

/**
 * 容量上限。目的是让一个阶段/一个练习不要无限膨胀——虽然日常使用根本碰不到，
 * 但真有人一口气练几百卷时，统计、上下文与文件体积都会变慢，提前兜住。
 *
 * 数字取的是「长期冲刺也用得完」的量级：按每天 2 卷算，20 卷 ≈ 3 周的量；
 * 50 个练习足够把整个技术栈切得很细了。要调整改这里就行。
 */
public final class Limits {

    /** 一个阶段下最多多少个练习 */
    public static final int MAX_PRACTICES_PER_STAGE = 50;

    /** 一个练习（一个会话）最多出多少张卷 */
    public static final int MAX_PAPERS_PER_PRACTICE = 20;

    /** 一条笔记的字数上限（快速记录，别写成文章） */
    /** 记事本全文上限（不是单条笔记——记事本是一整块文本，随手记会越写越长） */
    public static final int MAX_NOTE_TEXT = 20000;

    /** 一个练习的笔记条数上限 */
    public static final int MAX_NOTES_PER_PRACTICE = 500;

    /** 一个练习的错题条数上限 */
    public static final int MAX_MISTAKES_PER_PRACTICE = 500;

    /** 一道题的追问消息上限（user + assistant 合计，40 条 = 20 轮） */
    public static final int MAX_FOLLOWUP_MESSAGES = 40;

    /** 单条追问内容上限（用户提问与 AI 回答都按这个截） */
    public static final int MAX_FOLLOWUP_TEXT = 2000;

    private Limits() {}
}
