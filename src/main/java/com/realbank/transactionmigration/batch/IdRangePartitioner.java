package com.realbank.transactionmigration.batch;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Partitions by booking_date daily range.
 * A single bounded GROUP BY query (partition-pruned) fetches
 * the min/max id per day — no full-table scan, no DISTINCT dedup.
 */
public class IdRangePartitioner implements Partitioner {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final LocalDate startDate;
    private final LocalDate endDate;

    public IdRangePartitioner(JdbcTemplate jdbcTemplate, LocalDate startDate, LocalDate endDate) {
        this.jdbcTemplate = jdbcTemplate;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        // Single query, MySQL prunes to only the relevant daily partitions
        String sql = """
                SELECT DATE(booking_date) AS booking_date, MIN(id) AS min_id, MAX(id) AS max_id
                FROM transaction_loan_history
                WHERE booking_date >= '%s'
                  AND booking_date <  DATE_ADD('%s', INTERVAL 1 DAY)
                GROUP BY DATE(booking_date)
                ORDER BY booking_date
                """.formatted(startDate.format(DATE_FMT), endDate.format(DATE_FMT));

        List<long[]> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new long[]{
                rs.getDate("booking_date").toLocalDate().toEpochDay(),
                rs.getLong("min_id"),
                rs.getLong("max_id")
        });

        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();

        if (rows.isEmpty()) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putString("bookingDate", "");
            ctx.putLong("minId", 0L);
            ctx.putLong("maxId", -1L);
            partitions.put("partition0", ctx);
            return partitions;
        }

        int index = 0;
        for (long[] row : rows) {
            LocalDate date = LocalDate.ofEpochDay(row[0]);
            ExecutionContext ctx = new ExecutionContext();
            ctx.putString("bookingDate", date.format(DATE_FMT));
            ctx.putLong("minId", row[1]);
            ctx.putLong("maxId", row[2]);
            partitions.put("partition" + index++, ctx);
        }

        return partitions;
    }
}
