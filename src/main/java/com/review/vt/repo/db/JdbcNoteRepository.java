package com.review.vt.repo.db;

import com.review.vt.domain.Note;
import com.review.vt.repo.NoteRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 练习笔记表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcNoteRepository extends AbstractJdbcRepo implements NoteRepository {

    private static final String COLS =
            "id, user_id, practice_id, question_id, scene, text, created_at, updated_at";

    private static final RowMapper<Note> MAPPER = (rs, i) -> {
        Note n = new Note();
        n.setId(rs.getLong("id"));
        n.setUserId(rs.getLong("user_id"));
        n.setPracticeId(rs.getLong("practice_id"));
        n.setQuestionId(rs.getLong("question_id"));
        n.setScene(rs.getString("scene"));
        n.setText(rs.getString("text"));
        n.setCreatedAt(rs.getLong("created_at"));
        n.setUpdatedAt(rs.getLong("updated_at"));
        return n;
    };

    public JdbcNoteRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Note> findById(Long id) {
        List<Note> list = jdbc.query("SELECT " + COLS + " FROM note WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Note save(Note n) {
        saveRow("note",
                new String[]{"user_id", "practice_id", "question_id", "scene", "text", "created_at", "updated_at"},
                new Object[]{uidOf(n.getUserId()), n.getPracticeId(), n.getQuestionId(),
                        n.getScene(), n.getText(), n.getCreatedAt(), n.getUpdatedAt()},
                n.getId(), n::setId);
        return n;
    }

    @Override
    public List<Note> findByPracticeIdOrderByCreatedAtDesc(Long practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM note WHERE user_id = ? AND practice_id = ? ORDER BY created_at DESC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public void deleteAll(List<Note> notes) {
        deleteByIds("note", notes.stream().map(Note::getId).toList());
    }
}
