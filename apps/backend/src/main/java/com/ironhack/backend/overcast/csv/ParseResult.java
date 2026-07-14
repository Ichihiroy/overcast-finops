package com.ironhack.backend.overcast.csv;

import com.ironhack.backend.overcast.domain.NormalizedResource;

import java.util.List;

/**
 * Provider-agnostic parse output — the only shape ScanService ingests,
 * whether the CSV was an Azure usage details export or an AWS CUR.
 */
public record ParseResult(List<NormalizedResource> resources, String currency, String provider,
                          boolean hasAssociationColumn, boolean hasAgeColumn) {}
