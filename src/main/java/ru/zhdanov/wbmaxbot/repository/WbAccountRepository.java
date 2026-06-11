package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import ru.zhdanov.wbmaxbot.model.WbAccount;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class WbAccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public WbAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WbAccount upsertConnected(String phoneNumber, String storageStateJson, OffsetDateTime now) {
        Optional<WbAccount> existing = findByPhoneNumber(phoneNumber);
        if (existing.isPresent()) {
            jdbcTemplate.update("""
                    update wb_account
                    set storage_state_json = ?, status = 'CONNECTED', last_error = null,
                        updated_at = ?, last_authenticated_at = ?
                    where id = ?
                    """,
                    storageStateJson,
                    now.toString(),
                    now.toString(),
                    existing.get().id()
            );
            return findById(existing.get().id()).orElseThrow();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into wb_account(phone_number, storage_state_json, status, last_error, created_at, updated_at, last_authenticated_at)
                    values (?, ?, 'CONNECTED', null, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, phoneNumber);
            statement.setString(2, storageStateJson);
            statement.setString(3, now.toString());
            statement.setString(4, now.toString());
            statement.setString(5, now.toString());
            return statement;
        }, keyHolder);

        long id = keyHolder.getKey() == null ? -1L : keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    public Optional<WbAccount> findById(long id) {
        List<WbAccount> rows = jdbcTemplate.query("""
                select id, phone_number, storage_state_json, status, last_error, created_at, updated_at, last_authenticated_at
                from wb_account
                where id = ?
                """,
                (rs, rowNum) -> mapRow(rs),
                id
        );
        return rows.stream().findFirst();
    }

    public Optional<WbAccount> findByPhoneNumber(String phoneNumber) {
        List<WbAccount> rows = jdbcTemplate.query("""
                select id, phone_number, storage_state_json, status, last_error, created_at, updated_at, last_authenticated_at
                from wb_account
                where phone_number = ?
                """,
                (rs, rowNum) -> mapRow(rs),
                phoneNumber
        );
        return rows.stream().findFirst();
    }

    private WbAccount mapRow(ResultSet rs) throws SQLException {
        return new WbAccount(
                rs.getLong("id"),
                rs.getString("phone_number"),
                rs.getString("storage_state_json"),
                rs.getString("status"),
                rs.getString("last_error"),
                DbTimeParser.parseRequired(rs.getString("created_at")),
                DbTimeParser.parseRequired(rs.getString("updated_at")),
                parseNullableOffsetDateTime(rs.getString("last_authenticated_at"))
        );
    }

    private OffsetDateTime parseNullableOffsetDateTime(String value) {
        return DbTimeParser.parseNullable(value);
    }
}
