package com.review.vt.service.migration;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 启动时执行 schema v2 迁移（幂等，已迁移会直接跳过）。 */
@Component
@Profile("!json")
@Order(1)
public class SchemaV2Runner implements CommandLineRunner {

    private final SchemaV2Migrator migrator;

    public SchemaV2Runner(SchemaV2Migrator migrator) {
        this.migrator = migrator;
    }

    @Override
    public void run(String... args) {
        migrator.run();
    }
}
