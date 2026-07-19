package com.example.txnmigrate.batch;

import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

public class IdRangePartitioner implements Partitioner {

    private final JdbcTemplate jdbcTemplate;
    private final String sourceTable;

    public IdRangePartitioner(DataSource sourceDataSource, String sourceTable) {
        this.jdbcTemplate = new JdbcTemplate(sourceDataSource);
        this.sourceTable = sourceTable;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Long minId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM " + sourceTable, Long.class);
        Long maxId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM " + sourceTable, Long.class);

        int effectiveGridSize = Math.max(1, gridSize);
        Map<String, ExecutionContext> result = new LinkedHashMap<>();

        if (minId == null || maxId == null) {
            ExecutionContext empty = new ExecutionContext();
            empty.putLong("minId", 0L);
            empty.putLong("maxId", -1L);
            result.put("partition-0", empty);
            return result;
        }

        long totalRange = (maxId - minId) + 1;
        long partitionSize = (long) Math.ceil((double) totalRange / effectiveGridSize);

        long start = minId;
        int index = 0;
        while (start <= maxId) {
            long end = Math.min(start + partitionSize - 1, maxId);
            ExecutionContext context = new ExecutionContext();
            context.putLong("minId", start);
            context.putLong("maxId", end);
            result.put("partition-" + index, context);
            start = end + 1;
            index++;
        }

        return result;
    }
}
