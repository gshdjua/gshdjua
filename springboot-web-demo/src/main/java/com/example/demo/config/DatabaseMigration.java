package com.example.demo.config;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class DatabaseMigration {
    private final DataSource dataSource;

    public DatabaseMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void ensureAvatarPathColumn() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ensureColumn(connection, "user", "avatar_path", "VARCHAR(500) DEFAULT ''");
            ensureColumn(connection, "user", "nickname", "VARCHAR(30) DEFAULT ''");
            ensureColumn(connection, "audio", "genre", "VARCHAR(40) NOT NULL DEFAULT '其他'");
            ensureVarcharCapacity(connection, "audio", "genre", 255, "VARCHAR(255) NOT NULL DEFAULT '其他'");
            ensureColumn(connection, "audio", "lyric_path", "VARCHAR(500) DEFAULT ''");
            ensureColumn(connection, "audio", "source", "VARCHAR(255) DEFAULT ''");
            ensureColumn(connection, "audio", "introduction", "TEXT");
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String definition) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void ensureVarcharCapacity(Connection connection, String tableName, String columnName,
                                       int minimumSize, String definition) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (!columns.next() || columns.getInt("COLUMN_SIZE") >= minimumSize) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + definition);
        }
    }
}
