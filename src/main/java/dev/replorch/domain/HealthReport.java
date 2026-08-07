package dev.replorch.domain;

import dev.replorch.spi.ErrorSignal;

import java.time.Instant;
import java.util.List;

public record HealthReport(
        String replication,
        Instant checkedAt,
        boolean subscriptionEnabled,
        long applyErrorCount,
        Long slotRetainedBytes,
        Boolean slotActive,
        List<ErrorSignal> signals,
        List<String> problems
) {}
