package com.review.vt.repo.db;

import com.review.vt.domain.Practice;
import com.review.vt.repo.PracticeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 练习表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcPracticeRepository extends AbstractJdbcRepo implements PracticeRepository {

    private static final String COLS =
            "id, user_id, stage_id, mode, topic, scope_mode, scope_text, level, intensity, paper_size, " +
            "review, status, memory_json, notes_text, notes_updated_at, digest_json, " +
            "created_at, updated_at, completed_at";

    private static final RowMapper<Practice> MAPPER = (rs, i) -> {
        Practice p = new Practice();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setStageId(rs.getLong("stage_id"));
        p.setMode(rs.getString("mode"));
        p.setTopic(rs.getString("topic"));
        p.setScopeMode(rs.getString("scope_mode"));
        p.setScopeText(rs.getString("scope_text"));
        p.setLevel(rs.getString("level"));
        p.setIntensity(rs.getString("intensity"));
        p.setPaperSize(rs.getInt("paper_size"));
        p.setReview(rs.getBoolean("review"));
        p.setStatus(rs.getString("status"));
        p.setMemoryJson(rs.getString("memory_json"));
        p.setNotesText(rs.getString("notes_text"));
        long nu = rs.getLong("notes_updated_at");
        p.setNotesUpdatedAt(rs.wasNull() ? null : nu);
        p.setDigestJson(rs.getString("digest_json"));
        p.setCreatedAt(rs.getLong("created_at"));
        p.setUpdatedAt(rs.getLong("updated_at"));
        long ca = rs.getLong("completed_at");
        p.setCompletedAt(rs.wasNull() ? null : ca);
        return p;
    };

    public JdbcPracticeRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Practice> findById(Long id) {
        List<Practice> list = jdbc.query("SELECT " + COLS + " FROM practice WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Practice save(Practice p) {
        saveRow("practice",
                new String[]{"user_id", "stage_id", "mode", "topic", "scope_mode", "scope_text", "level",
                        "intensity", "paper_size", "review", "status", "memory_json", "notes_text",
                        "notes_updated_at", "digest_json", "created_at", "updated_at", "completed_at"},
                new Object[]{uidOf(p.getUserId()), p.getStageId(), p.getMode(), p.getTopic(),
                        p.getScopeMode(), p.getScopeText(), p.getLevel(), p.getIntensity(), p.getPaperSize(),
                        p.isReview(), p.getStatus(), p.getMemoryJson(), p.getNotesText(), p.getNotesUpdatedAt(),
                        p.getDigestJson(), p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt()},
                p.getId(), p::setId);
        return p;
    }

    @Override
    public List<Practice> findByStageIdOrderByCreatedAtAsc(Long stageId) {
        return jdbc.query("SELECT " + COLS + " FROM practice WHERE user_id = ? AND stage_id = ? ORDER BY created_at ASC",
                MAPPER, userId(), stageId);
    }

    @Override
    public List<Practice> findByStageIdOrderByCreatedAtDesc(Long stageId) {
        return jdbc.query("SELECT " + COLS + " FROM practice WHERE user_id = ? AND stage_id = ? ORDER BY created_at DESC",
                MAPPER, userId(), stageId);
    }

    @Override
    public void deleteAll(List<Practice> practices) {
        deleteByIds("practice", practices.stream().map(Practice::getId).toList());
    }
}
