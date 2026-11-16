package com.ddareungi.realtime.domain;

/**
 * Publication gate carried by a versioned realtime value (spec v5 11.0, internal fields {@code publicationEligible}
 * and {@code operationStatus}; never exposed in user DTOs).
 * <ul>
 *   <li>{@link #ELIGIBLE}: {@code publicationEligible == true} and {@code operationStatus == "ACTIVE"}.</li>
 *   <li>{@link #INELIGIBLE}: either field says otherwise (false, a non-ACTIVE status, or a malformed value).</li>
 *   <li>{@link #MISSING}: no ineligible field, but at least one of the two is absent or null.</li>
 * </ul>
 * Versioned values are public only when {@link #ELIGIBLE} (D3, strict: MISSING is excluded too). The legacy
 * per-station layout has no gate at all, so legacy values are checked only for valid id, name and coordinates
 * (documented limitation until the collector writes the versioned layout).
 */
public enum PublicationGate {
    ELIGIBLE,
    INELIGIBLE,
    MISSING;

    /** Combines the two gate fields: any INELIGIBLE wins, then any MISSING, else ELIGIBLE. */
    public static PublicationGate of(PublicationGate publicationEligible, PublicationGate operationStatus) {
        if (publicationEligible == INELIGIBLE || operationStatus == INELIGIBLE) return INELIGIBLE;
        if (publicationEligible == MISSING || operationStatus == MISSING) return MISSING;
        return ELIGIBLE;
    }
}
