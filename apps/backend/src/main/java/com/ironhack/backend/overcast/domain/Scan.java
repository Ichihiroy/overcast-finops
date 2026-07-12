package com.ironhack.backend.overcast.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Scan(
        String id,
        String sourceCloud,
        String filename,
        Instant uploadedAt,
        String currency,
        BigDecimal totalMonthlyCost,
        BigDecimal totalMonthlyWaste,
        // Human-readable data-quality notes (e.g. raw export missing enrichment
        // columns), newline-separated; null when the export was fully enriched.
        String dataNotes) {}
