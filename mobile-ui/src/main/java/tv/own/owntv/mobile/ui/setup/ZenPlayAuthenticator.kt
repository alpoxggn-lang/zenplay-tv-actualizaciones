package tv.own.owntv.mobile.ui.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import tv.own.owntv.core.network.HttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class ZenPlayAuthResult {
    ACTIVE,
    INVALID_CREDENTIALS,
    EXPIRED,
    UNREACHABLE,
    INVALID_RESPONSE,
}

/** Validates the chosen account before creating any profile or saving its credentials. */
class ZenPlayAuthenticator(private val http: HttpClient) {
    suspend fun authenticate(serverUrl: String, username: String, password: String): ZenPlayAuthResult {
        currentCoroutineContext().ensureActive()
        val url = zenPlayLoginUrl(serverUrl, username, password)
            ?: return ZenPlayAuthResult.INVALID_RESPONSE
        return try {
            http.get(url) { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Read at most one extra byte to distinguish a full response from an oversized one.
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_AUTH_RESPONSE_BYTES + 1 - bytes.size()))
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size() > MAX_AUTH_RESPONSE_BYTES) return@get ZenPlayAuthResult.INVALID_RESPONSE
                }
                parseZenPlayAuthResponse(bytes.toString(Charsets.UTF_8.name()))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HttpClient.HttpStatusException) {
            currentCoroutineContext().ensureActive()
            if (failure.code == 401 || failure.code == 403) {
                ZenPlayAuthResult.INVALID_CREDENTIALS
            } else {
                ZenPlayAuthResult.UNREACHABLE
            }
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            ZenPlayAuthResult.UNREACHABLE
        }
    }
}

private const val MAX_AUTH_RESPONSE_BYTES = 64 * 1024

/** Keeps the configured host, port and optional base path; credentials are query-encoded once. */
internal fun zenPlayLoginUrl(serverUrl: String, username: String, password: String): String? {
    val base = serverUrl.trim().toHttpUrlOrNull() ?: return null
    if (base.username.isNotEmpty() || base.password.isNotEmpty()) return null
    if (base.query != null || base.fragment != null) return null
    return base.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

/** No absent field or malformed response can grant access. Time is injectable for boundary tests. */
internal fun parseZenPlayAuthResponse(
    body: String,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): ZenPlayAuthResult {
    if (body.isBlank() || body.length > MAX_AUTH_RESPONSE_BYTES) return ZenPlayAuthResult.INVALID_RESPONSE
    return try {
        val tokens = JSONTokener(body)
        val root = tokens.nextValue() as? JSONObject ?: return ZenPlayAuthResult.INVALID_RESPONSE
        if (tokens.nextClean() != '\u0000') return ZenPlayAuthResult.INVALID_RESPONSE
        val user = root.optJSONObject("user_info") ?: return ZenPlayAuthResult.INVALID_RESPONSE
        val auth = user.opt("auth")
        val authCode = when (auth) {
            null -> return ZenPlayAuthResult.INVALID_RESPONSE
            is Number -> when (auth.toDouble()) {
                0.0 -> 0
                1.0 -> 1
                else -> return ZenPlayAuthResult.INVALID_RESPONSE
            }
            is String -> when (auth.trim()) {
                "0" -> 0
                "1" -> 1
                else -> return ZenPlayAuthResult.INVALID_RESPONSE
            }
            else -> return ZenPlayAuthResult.INVALID_RESPONSE
        }
        if (authCode == 0) return ZenPlayAuthResult.INVALID_CREDENTIALS

        val status = (user.opt("status") as? String)?.trim()
            ?: return ZenPlayAuthResult.INVALID_RESPONSE
        if (status.equals("Expired", ignoreCase = true)) return ZenPlayAuthResult.EXPIRED
        if (status.equals("Disabled", ignoreCase = true) || status.equals("Banned", ignoreCase = true)) {
            return ZenPlayAuthResult.INVALID_CREDENTIALS
        }
        if (!status.equals("Active", ignoreCase = true)) return ZenPlayAuthResult.INVALID_RESPONSE

        val expiryValue = user.opt("exp_date")
        val expiry = when {
            expiryValue == null || expiryValue == JSONObject.NULL || expiryValue == "" -> null
            expiryValue is Number || expiryValue is String -> expiryValue.toString().trim().toLongOrNull()
                ?: return ZenPlayAuthResult.INVALID_RESPONSE
            else -> return ZenPlayAuthResult.INVALID_RESPONSE
        }
        when {
            expiry != null && expiry < 0L -> ZenPlayAuthResult.INVALID_RESPONSE
            expiry != null && expiry > 0L && expiry <= nowEpochSeconds -> ZenPlayAuthResult.EXPIRED
            else -> ZenPlayAuthResult.ACTIVE
        }
    } catch (_: JSONException) {
        ZenPlayAuthResult.INVALID_RESPONSE
    }
}
