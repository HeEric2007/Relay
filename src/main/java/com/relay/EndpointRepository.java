package com.relay;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EndpointRepository {

    private final JdbcClient db;

    EndpointRepository(JdbcClient db) {
        this.db = db;
    }

    public Endpoint insert(String url, String secret, List<String> eventTypes) {
        return db.sql("""
                insert into endpoints (url, secret, event_types)
                values (:url, :secret, :eventTypes)
                returning *
                """)
                .param("url", url)
                .param("secret", secret)
                .param("eventTypes", eventTypes.toArray(new String[0]))
                .query(EndpointRepository::mapRow)
                .single();
    }

    public Optional<Endpoint> findById(UUID id) {
        return db.sql("select * from endpoints where id = :id")
                .param("id", id)
                .query(EndpointRepository::mapRow)
                .optional();
    }

    public List<Endpoint> findAll() {
        return db.sql("select * from endpoints order by created_at desc")
                .query(EndpointRepository::mapRow)
                .list();
    }

    static Endpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
        String[] eventTypes = (String[]) rs.getArray("event_types").getArray();
        return new Endpoint(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                rs.getString("secret"),
                List.of(eventTypes),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
