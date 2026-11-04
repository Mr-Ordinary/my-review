package com.review.vt.repo.db;

import com.review.vt.domain.Paper;
import com.review.vt.repo.PaperRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 卷表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcPaperRepository extends AbstractJdbcRepo implements PaperRepository {

    private static final String COLS =
            "id, user_id, practice_id, sequence, kind, focus_points_json, composition_mode, status, " +
            "summary_json, progress_json, plan_json, max_score, created_at, submitted_at";

    private static final RowMapper<Paper> MAPPER = (rs, i) -> {
        Paper p = new Paper();
        p.setId(rs.getString("id"));
        p.setUserId(rs.getString("user_id"));
        p.setPracticeId(rs.getString("practice_id"));
        p.setSequence(rs.getInt("sequence"));
        p.setKind(rs.getString("kind"));
        p.setFocusPointsJson(rs.getString("focus_points_json"));
        p.setCompositionMode(rs.getString("composition_mode"));
        p.setStatus(rs.getString("status"));
        p.setSummaryJson(rs.getString("summary_json"));
        p.setProgressJson(rs.getString("progress_json"));
        p.setPlanJson(rs.getString("plan_json"));
        p.setMaxScore(rs.getInt("max_score"));
        p.setCreatedAt(rs.getLong("created_at"));
        long sa = rs.getLong("submitted_at");
        p.setSubmittedAt(rs.wasNull() ? null : sa);
        return p;
    };

    public JdbcPaperRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Paper> findById(String id) {
        List<Paper> list = jdbc.query("SELECT " + COLS + " FROM paper WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Paper save(Paper p) {
        jdbc.update("INSERT INTO paper (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, practice_id=EXCLUDED.practice_id, " +
                        "sequence=EXCLUDED.sequence, kind=EXCLUDED.kind, focus_points_json=EXCLUDED.focus_points_json, " +
                        "composition_mode=EXCLUDED.composition_mode, status=EXCLUDED.status, " +
                        "summary_json=EXCLUDED.summary_json, progress_json=EXCLUDED.progress_json, " +
                        "plan_json=EXCLUDED.plan_json, max_score=EXCLUDED.max_score, " +
                        "created_at=EXCLUDED.created_at, submitted_at=EXCLUDED.submitted_at",
                p.getId(), uidOf(p.getUserId()), p.getPracticeId(), p.getSequence(), p.getKind(),
                p.getFocusPointsJson(), p.getCompositionMode(), p.getStatus(),
                p.getSummaryJson(), p.getProgressJson(), p.getPlanJson(), p.getMaxScore(),
                p.getCreatedAt(), p.getSubmittedAt());
        return p;
    }

    @Override
    public List<Paper> findByPracticeIdOrderBySequenceAsc(String practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM paper WHERE user_id = ? AND practice_id = ? ORDER BY sequence ASC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public long countByPracticeId(String practiceId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM paper WHERE user_id = ? AND practice_id = ?",
                Long.class, userId(), practiceId);
        return n == null ? 0 : n;
    }

    @Override
    public void deleteAll(List<Paper> papers) {
        deleteByIds("paper", papers.stream().map(Paper::getId).toList());
    }
}
