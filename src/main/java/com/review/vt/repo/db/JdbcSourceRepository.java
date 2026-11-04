package com.review.vt.repo.db;

import com.review.vt.domain.Source;
import com.review.vt.repo.SourceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 资料缓存表 PostgreSQL 实现。**全局共享、无 user_id**——
 * URL 抓取缓存是公开网页内容，多用户复用省抓取，不泄露任何用户数据。
 */
@Repository
@Profile("!json")
public class JdbcSourceRepository implements SourceRepository {

    private static final String COLS = "id, url, title, text, fetched_at, error";

    private static final RowMapper<Source> MAPPER = (rs, i) -> {
        Source s = new Source();
        s.setId(rs.getString("id"));
        s.setUrl(rs.getString("url"));
        s.setTitle(rs.getString("title"));
        s.setText(rs.getString("text"));
        s.setFetchedAt(rs.getLong("fetched_at"));
        s.setError(rs.getString("error"));
        return s;
    };

    private final JdbcTemplate jdbc;

    public JdbcSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Source> findById(String url) {
        List<Source> list = jdbc.query("SELECT " + COLS + " FROM source WHERE id = ?", MAPPER, url);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Source save(Source s) {
        jdbc.update("INSERT INTO source (" + COLS + ") VALUES (?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET url=EXCLUDED.url, title=EXCLUDED.title, " +
                        "text=EXCLUDED.text, fetched_at=EXCLUDED.fetched_at, error=EXCLUDED.error",
                s.getId(), s.getUrl(), s.getTitle(), s.getText(), s.getFetchedAt(), s.getError());
        return s;
    }

    @Override
    public List<Source> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM source", MAPPER);
    }
}
