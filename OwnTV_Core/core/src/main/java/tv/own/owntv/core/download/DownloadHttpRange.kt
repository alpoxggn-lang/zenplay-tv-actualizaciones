package tv.own.owntv.core.download

/** Parsed form of an HTTP `Content-Range` response header. */
internal data class DownloadContentRange(
    val start: Long?,
    val endInclusive: Long?,
    val total: Long?,
)

internal enum class DownloadWriteMode { APPEND, RESTART, ALREADY_COMPLETE }

internal data class DownloadResponsePlan(
    val mode: DownloadWriteMode,
    val expectedTotalBytes: Long?,
    val expectedBodyBytes: Long? = null,
)

/**
 * Parses both successful ranges (`bytes 100-199/500`) and the unsatisfied form used by 416
 * (the `bytes` wildcard form followed by `/500`). Invalid or arithmetically impossible ranges are
 * rejected fail-closed.
 */
internal fun parseDownloadContentRange(value: String?): DownloadContentRange? {
    if (value.isNullOrBlank()) return null
    val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (total != null && total < 0L) return null
    if (match.groupValues[1].isBlank()) {
        return DownloadContentRange(start = null, endInclusive = null, total = total)
    }
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start < 0L || end < start || (total != null && end >= total)) return null
    return DownloadContentRange(start, end, total)
}

/**
 * Decides whether a media response can safely extend an authenticated partial container.
 *
 * A 206 is appendable only when its first byte is exactly [existingBytes]. A 200 means the server
 * ignored Range and therefore needs a fresh encrypted target. A 416 is treated as completion only
 * when the server explicitly states that its total length equals the locally authenticated byte
 * count. All other responses are rejected instead of producing a plausible but corrupt download.
 */
internal fun planDownloadResponse(
    existingBytes: Long,
    responseCode: Int,
    contentRangeHeader: String?,
    contentLength: Long,
): DownloadResponsePlan? {
    if (existingBytes < 0L) return null
    val range = parseDownloadContentRange(contentRangeHeader)
    if (responseCode == 416) {
        return if (
            existingBytes > 0L &&
            range?.start == null &&
            range?.total == existingBytes
        ) {
            DownloadResponsePlan(DownloadWriteMode.ALREADY_COMPLETE, existingBytes, expectedBodyBytes = 0L)
        } else {
            null
        }
    }
    if (responseCode == 206) {
        val validRange = range ?: return null
        val start = validRange.start ?: return null
        val end = validRange.endInclusive ?: return null
        if (start != existingBytes) return null
        val rangeLength = runCatching { Math.addExact(Math.subtractExact(end, start), 1L) }
            .getOrNull() ?: return null
        if (contentLength >= 0L && contentLength != rangeLength) return null
        // `bytes start-end/*` does not prove that `end` is the resource EOF. Treating the slice
        // length as the total would authenticate a truncated file as complete.
        val total = validRange.total ?: return null
        return DownloadResponsePlan(
            mode = if (existingBytes == 0L) DownloadWriteMode.RESTART else DownloadWriteMode.APPEND,
            expectedTotalBytes = total,
            expectedBodyBytes = rangeLength,
        )
    }
    if (responseCode != 200) return null
    return DownloadResponsePlan(
        DownloadWriteMode.RESTART,
        contentLength.takeIf { it >= 0L },
        contentLength.takeIf { it >= 0L },
    )
}

/** True when EOF covers the whole object promised by the response rather than a shorter CDN slice. */
internal fun completesDownloadResponse(
    plan: DownloadResponsePlan,
    existingBytes: Long,
    receivedBytes: Long,
): Boolean {
    if (existingBytes < 0L || receivedBytes < 0L) return false
    if (plan.expectedBodyBytes != null && receivedBytes != plan.expectedBodyBytes) return false
    if (plan.mode == DownloadWriteMode.ALREADY_COMPLETE) return receivedBytes == 0L
    val expected = plan.expectedTotalBytes ?: return true
    val base = if (plan.mode == DownloadWriteMode.APPEND) existingBytes else 0L
    val actual = runCatching { Math.addExact(base, receivedBytes) }.getOrNull() ?: return false
    return actual == expected
}

/** Rejects a body block before it can commit bytes beyond the response's declared range. */
internal fun acceptsDownloadResponseBlock(
    plan: DownloadResponsePlan,
    receivedBytes: Long,
    nextBytes: Int,
): Boolean {
    if (receivedBytes < 0L || nextBytes < 0) return false
    val expected = plan.expectedBodyBytes ?: return true
    return receivedBytes <= expected && nextBytes.toLong() <= expected - receivedBytes
}

private val CONTENT_RANGE = Regex("bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\*|\\d+)", RegexOption.IGNORE_CASE)
