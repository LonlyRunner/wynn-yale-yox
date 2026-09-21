package io.github.lonlyrunner.wynn;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration
class MySqlSchemaMigration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CommandLineRunner migrateLongTextColumns(DataSource dataSource) {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE posts MODIFY COLUMN content_zh LONGTEXT");
                    statement.executeUpdate("ALTER TABLE posts MODIFY COLUMN content_en LONGTEXT");
                    statement.executeUpdate("ALTER TABLE knowledge_entries MODIFY COLUMN content LONGTEXT NOT NULL");
                    statement.executeUpdate("ALTER TABLE journal_entries MODIFY COLUMN content_zh LONGTEXT NOT NULL");
                    statement.executeUpdate("ALTER TABLE journal_entries MODIFY COLUMN content_en LONGTEXT");
                }
            }
        };
    }
}
