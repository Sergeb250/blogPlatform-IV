package com.blogPlatform.III.blog.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// @Index can't create a FULLTEXT index, so it is created here once (after the seeder: @Order(2))
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class FullTextIndexInitializer implements ApplicationRunner {

    static final String INDEX_NAME = "ft_posts_title_content";

    private final JdbcTemplate jdbc;
    private final SchemaHelper schema;

    @Override
    public void run(ApplicationArguments args) {
        if (!schema.indexExists("posts", INDEX_NAME)) {
            log.info("Creating FULLTEXT index {} on posts(title, content) ...", INDEX_NAME);
            jdbc.execute("CREATE FULLTEXT INDEX " + INDEX_NAME + " ON posts (title, content)");
            log.info("FULLTEXT index created");
        }
    }
}
