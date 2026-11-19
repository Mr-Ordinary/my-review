package com.review.vt.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 批量写拆条：整批一次 SQL，只有超过 BATCH_CHUNK 才拆成多条。 */
class BatchWriterTest {

    @Test
    @DisplayName("空列表不调用 writer；不超长一条；超长按 500 拆条")
    void chunksOnlyWhenTooLong() {
        List<List<Integer>> calls = new ArrayList<>();

        BatchWriter.<Integer>write(List.of(), calls::add);
        assertTrue(calls.isEmpty(), "空列表不写");

        BatchWriter.write(java.util.stream.IntStream.range(0, 500).boxed().toList(), calls::add);
        assertEquals(1, calls.size(), "500 行仍然一条");
        assertEquals(500, calls.get(0).size());

        BatchWriter.write(java.util.stream.IntStream.range(0, 1201).boxed().toList(), calls::add);
        assertEquals(1 + 3, calls.size(), "1201 行拆 500+500+201");
        assertEquals(500, calls.get(1).size());
        assertEquals(500, calls.get(2).size());
        assertEquals(201, calls.get(3).size());
    }
}
