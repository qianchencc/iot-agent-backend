package com.ehe.iotagent.storage.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

abstract class AbstractJdbcFactSupport {
    protected static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    protected final JdbcTemplate jdbc;
    protected final TransactionTemplate transactions;
    protected final ObjectMapper mapper;

    AbstractJdbcFactSupport(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    protected <T> Optional<T> one(String sql, RowMapper<T> rowMapper, Object... args) {
        return jdbc.query(sql, rowMapper, args).stream().findFirst();
    }

    protected static Timestamp time(Instant value) {
        return Timestamp.from(value);
    }

    protected Map<String, Object> json(String value) {
        try {
            return mapper.readValue(value, MAP);
        } catch (Exception error) {
            return Map.of();
        }
    }

    protected static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    protected static Timestamp instant(Object value) {
        try {
            return value == null ? null : time(Instant.parse(String.valueOf(value)));
        } catch (Exception error) {
            return null;
        }
    }

    protected static java.sql.Date date(Object value) {
        try {
            return value == null ? null : java.sql.Date.valueOf(String.valueOf(value));
        } catch (Exception error) {
            return null;
        }
    }

    protected static String jsonText(Object value) {
        return value == null ? "{}" : value instanceof String string ? string : String.valueOf(value);
    }

    protected static String required(ResultSet rs, int column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) throw new SQLException("required storage column is null: " + column);
        return value;
    }
}
