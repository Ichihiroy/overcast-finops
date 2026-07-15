package com.ironhack.backend.overcast.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One billed resource, normalized from the cloud CSV — the only shape the
 * rules engine ever sees, which is what keeps an AWS CUR adapter possible
 * (see docs/csv-schema.md).
 *
 * @param associatedResource null = the CSV had no association column (rules
 *        needing it stay silent); empty = column present and the resource is
 *        known to be attached to nothing.
 * @param ageDays null = unknown (column absent).
 * @param quantity null = the export had no usage column (the sustained-hours
 *        rules then assume always-on — see RulesEngine#sustained).
 */
public record NormalizedResource(
        String resourceId,
        String resourceType,
        ResourceKind kind,
        String resourceGroup,
        String region,
        String meter,
        String sku,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal monthlyCost,
        Map<String, String> tags,
        String associatedResource,
        Integer ageDays) {

    /** Short display name — the last segment of an Azure resource id. */
    public String shortName() {
        int idx = resourceId.lastIndexOf('/');
        return idx >= 0 ? resourceId.substring(idx + 1) : resourceId;
    }
}
