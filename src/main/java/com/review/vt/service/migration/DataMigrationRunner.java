package com.review.vt.service.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 一次性迁移启动器：应用启动后跑 {@link JsonToDbMigrator}。
 *
 * <p>开关 {@code myreview.migration.enabled}（默认 true）；迁移器自身幂等
 * （INSERT ON CONFLICT DO NOTHING），重复执行不会翻倍。json profile 下不启动。
 */
@Component
@Profile("!json")
public class DataMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationRunner.class);

    private final JsonToDbMigrator migrator;
    private final boolean enabled;

    public DataMigrationRunner(JsonToDbMigrator migrator,
                               @Value("${myreview.migration.enabled:false}") boolean enabled) {
        this.migrator = migrator;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("数据迁移未启用（myreview.migration.enabled=false），跳过");
            return;
        }
        migrator.run();
    }
}
