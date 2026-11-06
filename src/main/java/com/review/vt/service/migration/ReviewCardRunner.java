package com.review.vt.service.migration;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 在 schema v2（自增主键）之后执行 v3（复习卡拆表）。 */
@Component
@Profile("!json")
@Order(2)
public class ReviewCardRunner implements CommandLineRunner {

    private final ReviewCardMigrator migrator;

    public ReviewCardRunner(ReviewCardMigrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public void run(String... args) {
        migrator.run();
    }
}
