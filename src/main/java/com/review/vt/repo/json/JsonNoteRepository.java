package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Note;
import com.review.vt.repo.NoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 练习笔记表 → data/store/notes.json */
@Repository
public class JsonNoteRepository extends AbstractJsonStore<Note> implements NoteRepository {

    public JsonNoteRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "notes.json"), Note::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Note> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Note save(Note note) {
        return super.save(note);
    }

    @Override
    public List<Note> findByPracticeIdOrderByCreatedAtDesc(String practiceId) {
        return all().stream()
                .filter(n -> practiceId.equals(n.getPracticeId()))
                .sorted(Comparator.comparingLong(Note::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public void deleteAll(List<Note> notes) {
        super.deleteAll(notes);
    }
}
