package ru.zhdanov.wbmaxbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import ru.zhdanov.wbmaxbot.model.ReportRow;
import ru.zhdanov.wbmaxbot.model.ScrapeResult;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;

@Repository
public class ReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long saveSuccessfulRun(ScrapeResult result, String source, String summaryJson, String messageText) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into report_run(scraped_at, source, status, summary_json, message_text, error_message)
                    values (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, result.scrapedAt().toString());
            statement.setString(2, source);
            statement.setString(3, "SUCCESS");
            statement.setString(4, summaryJson);
            statement.setString(5, messageText);
            statement.setString(6, null);
            return statement;
        }, keyHolder);

        long runId = keyHolder.getKey() == null ? -1L : keyHolder.getKey().longValue();
        for (ReportRow row : result.rows()) {
            jdbcTemplate.update("""
                    insert into report_row_snapshot(
                        run_id, lo_name, auto_requests, pickup_time, route, parking,
                        boxes, kgt, shk, norm, ratio, volume_liters, avg_accumulation_liters,
                        distance_km, raw_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    row.loName(),
                    row.autoRequests(),
                    row.pickupTime(),
                    row.route(),
                    row.parking(),
                    row.boxes(),
                    row.kgt(),
                    row.shk(),
                    row.norm(),
                    row.ratio(),
                    row.volumeLiters(),
                    row.averageAccumulationLiters(),
                    row.distanceKm(),
                    row.rawJson()
            );
        }
        return runId;
    }

    public void saveFailedRun(String source, String summaryJson, String errorMessage) {
        jdbcTemplate.update("""
                insert into report_run(scraped_at, source, status, summary_json, message_text, error_message)
                values (?, ?, ?, ?, ?, ?)
                """,
                OffsetDateTime.now().toString(),
                source,
                "FAILED",
                summaryJson,
                null,
                errorMessage
        );
    }
}
