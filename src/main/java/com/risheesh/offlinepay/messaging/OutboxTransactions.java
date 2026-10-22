package com.risheesh.offlinepay.messaging;

import lombok.RequiredArgsConstructor;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OutboxTransactions {
    private final OutboxRepository outbox;
    private final Clock clock;
    public record Claim(UUID id, UUID token, String payload) {}

    @Transactional(readOnly = true)
    public List<UUID> ready() { return outbox.ready(clock.instant(), PageRequest.of(0, 20)); }

    @Transactional
    public Optional<Claim> claim(UUID id) {
        var row = outbox.lock(id).orElseThrow(); Instant now = clock.instant();
        boolean ready = "PENDING".equals(row.getStatus()) && !row.getNextAttemptAt().isAfter(now);
        boolean abandoned = "PROCESSING".equals(row.getStatus()) && !row.getLeaseUntil().isAfter(now);
        if (!ready && !abandoned) return Optional.empty();
        return Optional.of(new Claim(id, row.claim(now), row.getPayload()));
    }
    @Transactional
    public void finish(Claim claim, String error) {
        var row = outbox.lock(claim.id()).orElseThrow();
        if (!row.owns(claim.token())) return; // A newer worker reclaimed the expired lease.
        if (error == null) row.published(clock.instant());
        else row.failed(clock.instant(), error);
    }
}
