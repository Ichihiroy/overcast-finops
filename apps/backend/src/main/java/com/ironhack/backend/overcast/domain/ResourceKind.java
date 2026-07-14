package com.ironhack.backend.overcast.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Coarse resource classification the rule predicates dispatch on. */
public enum ResourceKind {
    VM, DISK, SNAPSHOT, PUBLIC_IP, OTHER;

    private static final Pattern ARM_ID_TYPE =
            Pattern.compile("/providers/([^/]+/[^/]+)/", Pattern.CASE_INSENSITIVE);

    public static ResourceKind fromAzureType(String resourceType) {
        if (resourceType == null) return OTHER;
        String t = resourceType.toLowerCase();
        if (t.endsWith("/virtualmachines")) return VM;
        if (t.endsWith("/disks")) return DISK;
        if (t.endsWith("/snapshots")) return SNAPSHOT;
        if (t.endsWith("/publicipaddresses")) return PUBLIC_IP;
        return OTHER;
    }

    /**
     * Fallback for exports whose type column carries a display name
     * ("Virtual machine"): the ARM id still embeds the real type segment.
     */
    public static ResourceKind fromAzureResourceId(String resourceId) {
        if (resourceId == null) return OTHER;
        Matcher m = ARM_ID_TYPE.matcher(resourceId);
        return m.find() ? fromAzureType(m.group(1)) : OTHER;
    }

    /** AWS CUR classification: product code plus the usage-type string. */
    public static ResourceKind fromAwsUsage(String productCode, String usageType) {
        String p = productCode == null ? "" : productCode.toLowerCase(Locale.ROOT);
        String u = usageType == null ? "" : usageType.toLowerCase(Locale.ROOT);
        if (!p.equals("amazonec2")) return OTHER;
        if (u.contains("ebs:snapshot")) return SNAPSHOT;
        if (u.contains("ebs:volume")) return DISK;
        if (u.contains("elasticip") || u.contains("idleaddress")) return PUBLIC_IP;
        if (u.contains("boxusage") || u.contains("spotusage")) return VM;
        return OTHER;
    }
}
