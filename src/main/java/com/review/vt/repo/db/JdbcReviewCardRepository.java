package com.review.vt.repo.db;

import com.review.vt.domain.ReviewCard;
import com.review.vt.repo.ReviewCardRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** review_card 表：一份复习提纲的每张学习卡一行。 */
@Repository
@Profile("!json")
public class JdbcReviewCardRepository extends AbstractJdbcRepo implements ReviewCardRepository {

    private static final String COLS =
            "id, user_id, review_doc_id, point_name, category, priority, content_json, sequence, " +
            "created_at, updated_at";

    private static final RowMapper<ReviewCard> MAPPER = (rs, i) -> {
        ReviewCard c = new ReviewCard();
        c.setId(rs.getLong("id"));
        c.setUserId(rs.getLong("user_id"));
        c.setReviewDocId(rs.getLong("review_doc_id"));
        c.setPointName(rs.getString("point_name"));
        c.setCategory(rs.getString("category"));
        c.setPriority(rs.getString("priority"));
        c.setContentJson(rs.getString("content_json"));
        c.setSequence(rs.getInt("sequence"));
        c.setCreatedAt(rs.getLong("created_at"));
        c.setUpdatedAt(rs.getLong("updated_at"));
        return c;
    };

    public JdbcReviewCardRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<ReviewCard> findByReviewDocIdOrderBySequenceAsc(Long reviewDocId) {
        return jdbc.query("SELECT " + COLS + " FROM review_card WHERE user_id = ? AND review_doc_id = ? " +
                        "ORDER BY sequence ASC",
                MAPPER, userId(), reviewDocId);
    }

    @Override
    public Optional<ReviewCard> findByReviewDocIdAndPointName(Long reviewDocId, String pointName) {
        List<ReviewCard> list = jdbc.query("SELECT " + COLS + " FROM review_card " +
                        "WHERE user_id = ? AND review_doc_id = ? AND point_name = ?",
                MAPPER, userId(), reviewDocId, pointName);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public ReviewCard save(ReviewCard card) {
        if (card.getId() == null) {
            // 同一份提纲同一个点只允许一张卡：先按唯一键找已有行，找到就转成更新
            findByReviewDocIdAndPointName(card.getReviewDocId(), card.getPointName())
                    .ifPresent(existing -> card.setId(existing.getId()));
        }
        saveRow("review_card",
                new String[]{"user_id", "review_doc_id", "point_name", "category", "priority",
                        "content_json", "sequence", "created_at", "updated_at"},
                new Object[]{uidOf(card.getUserId()), card.getReviewDocId(), card.getPointName(),
                        card.getCategory(), card.getPriority(), card.getContentJson(), card.getSequence(),
                        card.getCreatedAt(), card.getUpdatedAt()},
                card.getId(), card::setId);
        return card;
    }

    @Override
    public void deleteByReviewDocId(Long reviewDocId) {
        jdbc.update("DELETE FROM review_card WHERE user_id = ? AND review_doc_id = ?",
                userId(), reviewDocId);
    }
}
