package com.condertech.protegeela.shared;

import com.condertech.protegeela.shared.exception.ResourceNotFoundException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Converts JDBC values to the JSON primitives expected by the web client. */
@Component
public class SqlRows {
    private final JdbcTemplate jdbc;
    public SqlRows(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        for (var row : rows) {
            row.replaceAll((key, value) -> value instanceof Timestamp time ? time.toInstant().toString()
                    : value instanceof UUID uuid ? uuid.toString() : value);
        }
        return rows;
    }

    public Map<String, Object> one(String sql, Object... args) {
        return list(sql, args).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Recurso nao encontrado."));
    }
}
