package tv.own.owntv.core.parser

import java.util.Locale

/** Account access takes precedence over an expiry timestamp supplied by a panel. */
enum class XtAccountAccess {
    ACTIVE,
    EXPIRED,
    REVOKED,
}

/**
 * Panels sometimes keep an old `exp_date` on a banned/disabled account. Such a date must not make
 * the UI claim that the subscription is active until that day. Authentication and explicit account
 * status are therefore classified before expiry is considered.
 */
fun XtreamClient.XtAccountDetails.accountAccess(): XtAccountAccess {
    if (!authOk) return XtAccountAccess.REVOKED
    val normalized = status?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (normalized == "expired" || normalized.contains("expir")) return XtAccountAccess.EXPIRED
    val revokedMarkers = listOf(
        "banned",
        "disabled",
        "invalid",
        "inactive",
        "suspended",
        "blocked",
        "revoked",
        "denied",
    )
    return if (revokedMarkers.any(normalized::contains)) XtAccountAccess.REVOKED
    else XtAccountAccess.ACTIVE
}

