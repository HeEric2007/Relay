package com.relay;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {

    /** The log views join events so a row says which event it carries, not just an id. */
    private static final String LOG_ROW_SELECT = """
            select d.id, d.event_id, e.type as event_type, d.endpoint_id, d.status,
                   d.attempts, d.run_at, d.last_status_code, d.last_error, d.created_at
              from deliveries d
              join events e on e.id = d.event_id
            """;

    private final JdbcClient db;

    DeliveryRepository(JdbcClient db) {
        this.db = db;
    }

    public List<UUID> fanOut(UUID eventId, String eventType) {
        return db.sql("""
                insert into deliveries (event_id, endpoint_id)
                select :eventId, id
                  from endpoints
                 where active
                   and (cardinality(event_types) = 0 or :eventType = any (event_types))
                returning id
                """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    /** SKIP LOCKED lets N workers share the table: claiming and locking are one statement. */
    public List<Delivery> claim(String workerId, int batchSize) {
        return db.sql("""
                update deliveries
                   set status = 'delivering',
                       locked_at = now(),
                       locked_by = :workerId
                 where id in (
                           select id
                             from deliveries
                            where status = 'pending'
                              and run_at <= now()
                            order by run_at
                            limit :batchSize
                              for update skip locked
                       )
                returning *
                """)
                .param("workerId", workerId)
                .param("batchSize", batchSize)
                .query(DeliveryRepository::mapRow)
                .list();
    }

    public void markDelivered(UUID id, int attempts, int statusCode) {
        db.sql("""
                update deliveries
                   set status = 'delivered',
                       attempts = :attempts,
                       last_status_code = :statusCode,
                       last_error = null
                 where id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("statusCode", statusCode)
                .update();
    }

    public void markRetry(UUID id, int attempts, Instant runAt, Integer statusCode, String error) {
        db.sql("""
                update deliveries
                   set status = 'pending',
                       attempts = :attempts,
                       run_at = :runAt,
                       locked_at = null,
                       locked_by = null,
                       last_status_code = :statusCode,
                       last_error = :error
                 where id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("runAt", Timestamp.from(runAt))
                .param("statusCode", statusCode)
                .param("error", error)
                .update();
    }

    public void markDead(UUID id, int attempts, Integer statusCode, String error) {
        db.sql("""
                update deliveries
                   set status = 'dead',
                       attempts = :attempts,
                       last_status_code = :statusCode,
                       last_error = :error
                 where id = :id
                """)
                .param("id", id)
                .param("attempts", attempts)
                .param("statusCode", statusCode)
                .param("error", error)
                .update();
    }

    /** Counts the lost attempt, so a delivery that keeps killing workers still dead-letters. */
    public int reclaimStuck(Instant lockedBefore) {
        return db.sql("""
                update deliveries
                   set status = 'pending',
                       attempts = attempts + 1,
                       run_at = now(),
                       locked_at = null,
                       locked_by = null,
                       last_error = 'reclaimed: worker lock expired'
                 where status = 'delivering'
                   and locked_at < :lockedBefore
                """)
                .param("lockedBefore", Timestamp.from(lockedBefore))
                .update();
    }

    /** Returns 0 if the delivery is not dead, which the API turns into a 409. */
    public int requeueFromDead(UUID id) {
        return db.sql("""
                update deliveries
                   set status = 'pending',
                       attempts = 0,
                       run_at = now(),
                       last_error = null
                 where id = :id
                   and status = 'dead'
                """)
                .param("id", id)
                .update();
    }

    public Optional<Delivery> findById(UUID id) {
        return db.sql("select * from deliveries where id = :id")
                .param("id", id)
                .query(DeliveryRepository::mapRow)
                .optional();
    }

    public List<DeliveryLogRow> findByEndpoint(UUID endpointId, int limit) {
        return db.sql(LOG_ROW_SELECT + """
                 where d.endpoint_id = :endpointId
                 order by d.created_at desc
                 limit :limit
                """)
                .param("endpointId", endpointId)
                .param("limit", limit)
                .query(DeliveryRepository::mapLogRow)
                .list();
    }

    public List<DeliveryLogRow> findRecent(int limit) {
        return db.sql(LOG_ROW_SELECT + " order by d.created_at desc limit :limit")
                .param("limit", limit)
                .query(DeliveryRepository::mapLogRow)
                .list();
    }

    public Map<String, Long> countsByStatus() {
        List<Map.Entry<String, Long>> rows = db
                .sql("select status, count(*) as total from deliveries group by status")
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("total")))
                .list();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("pending", "delivering", "delivered", "dead")) {
            counts.put(status, 0L);
        }
        for (Map.Entry<String, Long> row : rows) {
            counts.put(row.getKey(), row.getValue());
        }
        return counts;
    }

    static DeliveryLogRow mapLogRow(ResultSet rs, int rowNum) throws SQLException {
        return new DeliveryLogRow(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                instantAt(rs, "run_at"),
                (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"),
                instantAt(rs, "created_at"));
    }

    static Delivery mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Delivery(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("status"),
                rs.getInt("attempts"),
                instantAt(rs, "run_at"),
                instantAt(rs, "locked_at"),
                rs.getString("locked_by"),
                (Integer) rs.getObject("last_status_code"),
                rs.getString("last_error"),
                instantAt(rs, "created_at"));
    }

    private static Instant instantAt(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }
}
