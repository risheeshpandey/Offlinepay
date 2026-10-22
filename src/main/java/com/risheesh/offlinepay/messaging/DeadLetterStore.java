package com.risheesh.offlinepay.messaging;

import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class DeadLetterStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Transactional
    public void archive(String topic, int partition, long offset, String key, String payload) {
        UUID id = UUID.nameUUIDFromBytes((topic + ":" + partition + ":" + offset).getBytes(StandardCharsets.UTF_8));
        // The topic/partition has one assigned consumer. Unique ID also handles offset redelivery.
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_event WHERE id=?", Integer.class, id);
        if (exists != null && exists > 0) return;
        jdbc.update("INSERT INTO dead_letter_event(id,topic,partition_number,offset_number,event_key,payload,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id, topic, partition, offset, key, payload, "OPEN", java.sql.Timestamp.from(clock.instant()));
    }
}
