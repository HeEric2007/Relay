package com.relay;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private final JdbcClient db;

    EventRepository(JdbcClient db) {
        this.db = db;
    }

    public Event insert(String type, String payload) {
        return db.sql("""
                insert into events (type, payload)
                values (:type, cast(:payload as jsonb))
                returning *
                """)
                .param("type", type)
                .param("payload", payload)
                .query(EventRepository::mapRow)
                .single();
    }

    public Optional<Event> findById(UUID id) {
        return db.sql("select * from events where id = :id")
                .param("id", id)
                .query(EventRepository::mapRow)
                .optional();
    }

    static Event mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Event(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("payload"),
                rs.getTimestamp("created_at").toInstant());
    }
}
