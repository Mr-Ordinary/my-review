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

    /** content_json 是 v3 前的遗留大字段，已迁到 review_card，不再读写 */
    private static final String COLS =
            "id, user_id, practice_id, status, progress_json, created_at, updated_at";

    private static final RowMapper<ReviewDoc> MAPPER = (rs, i) -> {
        ReviewDoc d = new ReviewDoc();
        d.setId(rs.getLong("id"));
        d.setUserId(rs.getLong("user_id"));
        d.setPracticeId(rs.getLong("practice_id"));
        d.setStatus(rs.getString("status"));
        d.setProgressJson(rs.getString("progress_json"));
        d.setCreatedAt(rs.getLong("created_at"));
        d.setUpdatedAt(rs.getLong("updated_at"));
        return d;
    };

    public JdbcReviewDocRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<ReviewDoc> findById(Long id) {
        List<ReviewDoc> list = jdbc.query("SELECT " + COLS + " FROM review_doc WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<ReviewDoc> findByPracticeId(Long practiceId) {
        List<ReviewDoc> list = jdbc.query("SELECT " + COLS + " FROM review_doc WHERE user_id = ? AND practice_id = ?",
                MAPPER, userId(), practiceId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public ReviewDoc save(ReviewDoc d) {
        saveRow("review_doc",
                new String[]{"user_id", "practice_id", "status", "progress_json",
                        "created_at", "updated_at"},
                new Object[]{uidOf(d.getUserId()), d.getPracticeId(), d.getStatus(),
                        d.getProgressJson(), d.getCreatedAt(), d.getUpdatedAt()},
                d.getId(), d::setId);
        return d;
    }

    @Override
    public void deleteAll(List<ReviewDoc> docs) {
        deleteByIds("review_doc", docs.stream().map(ReviewDoc::getId).toList());
    }
}
