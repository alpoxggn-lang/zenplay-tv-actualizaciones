package tv.own.owntv.core.update

/** Parsed form of an HTTP `Content-Range` header. */
internal data class UpdateContentRange(
    val start: Long?,
    val endInclusive: Long?,
    val total: Long?,
)

internal enum class UpdateWriteMode { APPEND, RESTART, ALREADY_COMPLETE }

internal data class UpdateResponsePlan(
    val mode: UpdateWriteMode,
    val expectedTotalBytes: Long?,
)

/** Strict enough to reject a CDN returning a different slice than the one requested. */
internal fun parseUpdateContentRange(value: String?): UpdateContentRange? {
    if (value.isNullOrBlank()) return null
    val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (match.groupValues[1].isBlank()) return UpdateContentRange(null, null, total)
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start < 0L || end < start || (total != null && end >= total)) return null
    return UpdateContentRange(start, end, total)
}

/**
 * Decides whether a response can safely extend the partial APK. A normal 200 after a Range request
 * means the server ignored `Range` or rejected `If-Range`, so the file must be truncated first.
 */
internal fun planUpdateResponse(
    existingBytes: Long,
    responseCode: Int,
    contentRangeHeader: String?,
    contentLength: Long,
): UpdateResponsePlan? {
    val range = parseUpdateContentRange(contentRangeHeader)
    if (responseCode == 416) {
        return if (existingBytes > 0L && range != null && range.start == null && range.total == existingBytes) {
            UpdateResponsePlan(UpdateWriteMode.ALREADY_COMPLETE, existingBytes)
        } else {
            null
        }
    }
    if (responseCode !in 200..299) return null
    if (responseCode == 206) {
        val validRange = range ?: return null
        if (validRange.start != existingBytes) return null
        val total = validRange.total ?: contentLength.takeIf { it >= 0L }?.let { existingBytes + it }
        return UpdateResponsePlan(
            if (existingBytes == 0L) UpdateWriteMode.RESTART else UpdateWriteMode.APPEND,
            total,
        )
    }
    if (responseCode != 200) return null
    return UpdateResponsePlan(
        UpdateWriteMode.RESTART,
        contentLength.takeIf { it >= 0L },
    )
}

private val CONTENT_RANGE = Regex("bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\*|\\d+)", RegexOption.IGNORE_CASE)
