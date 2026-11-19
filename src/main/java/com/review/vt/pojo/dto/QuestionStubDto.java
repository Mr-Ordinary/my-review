package com.review.vt.pojo.dto;

/** 题目存根（题号导航用）：不含题面大文本；answered/revealed 供导航标记。 */
public record QuestionStubDto(String id, String paperId, int sequence,
                              String qtype, String tier, String questionType, Integer points,
                              boolean answered, boolean revealed) {}
