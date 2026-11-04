package com.review.vt.repo.db;

import com.review.vt.domain.ReviewDoc;
import com.review.vt.repo.ReviewDocRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 复习提纲表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcReviewDocRepository extends AbstractJdbcRepo implements ReviewDocRepository {

    private static final String COLS =
            "id, user_id, practice_id, status, content_json, progress_json, created_at, updated_at";

    private static final RowMapper<ReviewDoc> MAPPER = (rs, i) -> {
        ReviewDoc d = new ReviewDoc();
        d.setId(rs.getString("id"));
        d.setUserId(rs.getString("user_id"));
        d.setPracticeId(rs.getString("practice_id"));
        d.setStatus(rs.getString("status"));
        d.setContentJson(rs.getString("content_json"));
        d.setProgressJson(rs.getString("progress_json"));
        d.setCreatedAt(rs.getLong("created_at"));
        d.setUpdatedAt(rs.getLong("updated_at"));
        return d;
    };

    public JdbcReviewDocRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<ReviewDoc> findById(String id) {
        List<ReviewDoc> list = jdbc.query("SELECT " + COLS + " FROM review_doc WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<ReviewDoc> findByPracticeId(String practiceId) {
        List<ReviewDoc> list = jdbc.query("SELECT " + COLS + " FROM review_doc WHERE user_id = ? AND practice_id = ?",
                MAPPER, userId(), practiceId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public ReviewDoc save(ReviewDoc d) {
        jdbc.update("INSERT INTO review_doc (" + COLS + ") VALUES (?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, practice_id=EXCLUDED.practice_id, " +
                        "status=EXCLUDED.status, content_json=EXCLUDED.content_json, " +
                        "progress_json=EXCLUDED.progress_json, created_at=EXCLUDED.created_at, " +
                        "updated_at=EXCLUDED.updated_at",
                d.getId(), uidOf(d.getUserId()), d.getPracticeId(), d.getStatus(),
                d.getContentJson(), d.getProgressJson(), d.getCreatedAt(), d.getUpdatedAt());
        return d;
    }

    @Override
    public void deleteAll(List<ReviewDoc> docs) {
        deleteByIds("review_doc", docs.stream().map(ReviewDoc::getId).toList());
    }
}
