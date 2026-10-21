package com.review.vt.repo;

import com.review.vt.domain.Note;

import java.util.List;
import java.util.Optional;

/**
 * 练习笔记存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 * 列表最新在前——复习场景想看的是最近记的，不是最早记的。
 */
public interface NoteRepository {
    Optional<Note> findById(String id);
    Note save(Note note);
    List<Note> findByPracticeIdOrderByCreatedAtDesc(String practiceId);
    void deleteAll(List<Note> notes);
}
