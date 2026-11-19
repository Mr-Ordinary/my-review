package com.review.vt.common.util;

import java.util.List;
import java.util.function.Consumer;

/**
 * 批量写纪律（严禁循环插入/逐行写）：整批一次 SQL，**只有列表过长才拆条**。
 * 拆条阈值 500 行/条——question 一行约 19 列 ≈ 9500 个绑定参数，远低于 PG 65535 上限。
 */
public final class BatchWriter {

    /** 单条 SQL 最多行数（超过拆成多条） */
    public static final int BATCH_CHUNK = 500;

    private BatchWriter() {}

    /** 整批按 {@link #BATCH_CHUNK} 拆条交给 writer（每条 SQL 一次调用）；空列表什么都不做 */
    public static <T> void write(List<T> rows, Consumer<List<T>> writer) {
        if (rows == null || rows.isEmpty()) return;
        for (int from = 0; from < rows.size(); from += BATCH_CHUNK) {
            writer.accept(List.copyOf(rows.subList(from, Math.min(from + BATCH_CHUNK, rows.size()))));
        }
    }
}
