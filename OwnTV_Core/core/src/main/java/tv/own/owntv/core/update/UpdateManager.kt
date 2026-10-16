package tv.own.owntv.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.CoreBuildInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates straight from GitHub Releases: checks the repo's latest release, compares its tag
 * with the installed version, downloads the release APK, and hands it to the system installer.
 * No server of our own — each app's releases CI publishes its APKs per tag. A host can require an
 * exact stable asset name (Normal/Legacy), while older hosts retain ABI-based selection.
 * **Which repository and asset are asked for is the host app's to say** — see [CoreBuildInfo].
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
) {
    data class UpdateInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        /** GitHub's optional `sha256:…` asset digest. Older releases may not expose one. */
        val sha256: String? = null,
    )

    sealed interface Failure {
        data class CheckHttp(val code: Int) : Failure
        data object NoCompatibleApk : Failure
        data object InvalidReleaseResponse : Failure
        data object CheckNetwork : Failure
        data class DownloadHttp(val code: Int) : Failure
        data object EmptyDownload : Failure
        data object DownloadNetwork : Failure
        data object Install : Failure

        /** Internal storage cannot hold the APK twice (download + install session). */
        data class NotEnoughSpace(val requiredBytes: Long) : Failure

        /** The file is truncated, unreadable, wrongly signed, or otherwise not a safe app update. */
        data object DamagedDownload : Failure

        /** The system installer refused the package; [message] is its own wording when it gave one. */
        data class InstallRejected(val message: String?) : Failure
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Failed(val failure: Failure, val retryInfo: UpdateInfo? = null) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class CheckHttpException(val code: Int) : IOException()
    private class DownloadHttpException(val code: Int) : IOException()
    private class NoCompatibleApkException : IOException()
    private class InvalidReleaseResponseException : IOException()
    private class EmptyDownloadException : IOException()
    private class NotEnoughSpaceException(val requiredBytes: Long) : IOException()
    private class DamagedDownloadException(reason: String) : IOException(reason)

    internal enum class DownloadRunResult { SUCCESS, RETRY, FAILURE }

    private data class DownloadSession(
        val info: UpdateInfo,
        val etag: String?,
        val lastModified: String?,
        val expectedBytes: Long,
        val updatedAtMs: Long,
    )

    private val updateDir = File(context.filesDir, "updates")
    private val updateFile = File(updateDir, "zenplay-update.apk")
    private val sessionPreferences = context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
    private val transferGate = UpdateTransferGate()
    private val installRecoveryGuard = Any()

    @Volatile
    private var installerCommitOwnedByProcess = false

    @Volatile
    private var installRecoveryInFlight = false

    val currentVersion: String = CoreBuildInfo.versionName

    init {
        cleanupStaleSession()
    }

    private fun failureFor(error: Throwable, checking: Boolean): Failure = when (error) {
        is CheckHttpException -> Failure.CheckHttp(error.code)
        is DownloadHttpException -> Failure.DownloadHttp(error.code)
        is NoCompatibleApkException -> Failure.NoCompatibleApk
        is InvalidReleaseResponseException -> Failure.InvalidReleaseResponse
        is EmptyDownloadException -> Failure.EmptyDownload
        is NotEnoughSpaceException -> Failure.NotEnoughSpace(error.requiredBytes)
        is DamagedDownloadException -> Failure.DamagedDownload
        else -> if (checking) Failure.CheckNetwork else Failure.DownloadNetwork
    }

    /** Queries GitHub's latest release; moves to Available / UpToDate / a semantic failure. */
    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch {
            runCatching {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${CoreBuildInfo.releaseRepo}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ZenPlay-TV")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw CheckHttpException(resp.code)
                    val body = resp.body.string()
                    if (body.isBlank()) throw InvalidReleaseResponseException()
                    val o = runCatching { JSONObject(body) }.getOrElse { throw InvalidReleaseResponseException() }
                    val version = o.optString("tag_name").removePrefix("v").takeIf { it.isNotBlank() }
                        ?: throw InvalidReleaseResponseException()
                    val notes = o.optString("body").take(16_000)
                    val assets = o.optJSONArray("assets") ?: throw InvalidReleaseResponseException()
                    val wantX86 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64"
                    val asset = selectReleaseApk(
                        assets = assets,
                        exactName = CoreBuildInfo.releaseAssetName,
                        wantX86 = wantX86,
                    )
                        ?: throw NoCompatibleApkException()
                    val apkUrl = asset.optString("browser_download_url")
                    val sha256 = asset.optString("digest")
                        .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.takeIf { it.length == 64 && it.all(Char::isLetterOrDigit) }
                    val info = UpdateInfo(version, notes, apkUrl, sha256)
                    // A persisted worker can resume at process start while this network check is
                    // still in flight. Reconcile/delete its staging file only under the same gate,
                    // and never let this older check result overwrite a worker that already owns it.
                    transferGate.exclusively {
                        if (_state.value !is State.Checking) return@exclusively
                        if (isNewer(version, currentVersion)) {
                            discardSessionUnlessMatching(info)
                            _state.value = State.Available(info)
                        } else {
                            clearDownloadSession()
                            _state.value = State.UpToDate
                        }
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "update check failed: ${error.message}", error)
                if (_state.value is State.Checking) {
                    _state.value = State.Failed(failureFor(error, checking = true))
                }
            }
        }
    }

    /** Persists the release and delegates the transfer to WorkManager's foreground service. */
    fun downloadAndInstall() {
        val info = (_state.value as? State.Available)?.info ?: return
        val persisted = readDownloadSession()?.takeIf { it.matches(info) }
        _state.value = State.Downloading(if (persisted != null) persistedProgress() else 0)
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(UpdateDownloadWorker.inputFor(info))
            // Android 12+ may defer an ordinary background job long enough that its foreground
            // promotion is no longer legal. Expedited work starts in the user-requested window;
            // devices without quota fall back to a normal constrained job instead of failing.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(UPDATE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPDATE_WORK_NAME,
            // Never cancel-and-replace a worker that may still be unwinding a blocking socket read.
            // The persisted request owns this one staging path until it reaches a terminal state.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Runs inside [UpdateDownloadWorker]; the result tells WorkManager whether a retry is useful. */
    internal suspend fun runQueuedDownload(
        requestedInfo: UpdateInfo?,
        onProgress: (Int) -> Unit,
    ): DownloadRunResult = transferGate.exclusively {
        withContext(Dispatchers.IO) {
            val fallbackInfo = readDownloadSession()?.info
            val info = requestedInfo ?: fallbackInfo ?: return@withContext DownloadRunResult.FAILURE
            val initial = prepareDownloadSession(info)
            val effectiveInfo = initial.info
            _state.value = State.Downloading(persistedProgress())
            try {
                updateDir.mkdirs()
                if (initial.expectedBytes > 0L && updateFile.length() == initial.expectedBytes) {
                    finishDownload(effectiveInfo)
                } else {
                    transferResumable(effectiveInfo, onProgress)
                    currentCoroutineContext().ensureActive()
                    finishDownload(effectiveInfo)
                }
                _state.value = State.Available(effectiveInfo) // usable again if installer is cancelled
                DownloadRunResult.SUCCESS
            } catch (error: Throwable) {
                // Coroutine cancellation is ownership transfer, not a failed download. Propagate it
                // so WorkManager can stop this run before another execution acquires the gate.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "update download failed: ${error.message}", error)
                val integrityFailure = error is DamagedDownloadException || error is EmptyDownloadException
                if (integrityFailure) clearDownloadSession()
                val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                _state.value = State.Failed(failure, retryInfo = effectiveInfo)
                when {
                    error is DownloadHttpException && error.code >= 500 -> DownloadRunResult.RETRY
                    error is IOException && error !is DamagedDownloadException &&
                        error !is EmptyDownloadException && error !is NotEnoughSpaceException &&
                        error !is InstallException -> DownloadRunResult.RETRY
                    else -> DownloadRunResult.FAILURE
                }
            }
        }
    }

    internal fun markWorkerLaunchFailure(requestedInfo: UpdateInfo?) {
        val info = requestedInfo ?: readDownloadSession()?.info
        _state.value = State.Failed(Failure.DownloadNetwork, retryInfo = info)
    }

    /**
     * A PackageInstaller confirmation Intent cannot be serialized safely. If Android killed the
     * background process that received it, recommit the retained, already verified APK and let the
     * new status callback provide a fresh Intent. The gate prevents this from racing a download.
     */
    internal fun recoverPendingInstallApproval() {
        synchronized(installRecoveryGuard) {
            if (!shouldRecoverInstallApproval(
                    durableApprovalPending = UpdateInstallRecoveryStore.isPending(context),
                    commitOwnedByThisProcess = installerCommitOwnedByProcess,
                    recoveryAlreadyRunning = installRecoveryInFlight,
                )
            ) {
                return
            }
            installRecoveryInFlight = true
        }
        scope.launch {
            try {
                transferGate.exclusively {
                    val session = readDownloadSession()
                    if (session == null || !updateFile.exists() || updateFile.length() <= 0L) {
                        abandonStaleInstallSession(context.packageManager.packageInstaller, force = true)
                        return@exclusively
                    }
                    try {
                        verifyDigest(updateFile, session.info.sha256)
                        verifyApk(updateFile)
                        install(updateFile)
                        _state.value = State.Available(session.info)
                    } catch (error: Throwable) {
                        Log.w(TAG, "could not recreate installer confirmation", error)
                        val integrityFailure = error is DamagedDownloadException || error is EmptyDownloadException
                        if (integrityFailure) clearDownloadSession()
                        abandonStaleInstallSession(context.packageManager.packageInstaller, force = true)
                        val failure = if (error is InstallException) Failure.Install else failureFor(error, checking = false)
                        _state.value = State.Failed(failure, retryInfo = session.info)
                    }
                }
            } finally {
                installRecoveryInFlight = false
            }
        }
    }

    private fun finishDownload(info: UpdateInfo) {
        if (!updateFile.exists() || updateFile.length() <= 0L) throw EmptyDownloadException()
        verifyDigest(updateFile, info.sha256)
        verifyApk(updateFile)
        install(updateFile)
        // Keep the verified APK + resume metadata until a terminal installer result. If Android
        // kills the process while confirmation is pending (or notifications are disabled), opening
        // ZenPlay and retrying recommits this exact verified file without downloading it again.
    }

    /** HTTP Range + If-Range: a changed release restarts cleanly; a matching one appends. */
    private suspend fun transferResumable(info: UpdateInfo, onProgress: (Int) -> Unit) {
        var restartedAfterInvalidRange = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val session = readDownloadSession()?.takeIf { it.matches(info) }
                ?: prepareDownloadSession(info)
            val existing = updateFile.length().coerceAtLeast(0L)
            val requestBuilder = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "ZenPlay-TV")
            if (existing > 0L) {
                requestBuilder.header("Range", "bytes=$existing-")
                val ifRange = session.etag?.takeUnless { it.startsWith("W/", ignoreCase = true) }
                    ?: session.lastModified
                ifRange?.let { requestBuilder.header("If-Range", it) }
            }
            val call = client.newCall(requestBuilder.build())
            val cancellationWatcher = watchTransferCancellation(call::cancel)
            try {
                call.execute().use { response ->
                    if (response.code !in 200..299 && response.code != 416) {
                        throw DownloadHttpException(response.code)
                    }
                    val plan = planUpdateResponse(
                        existingBytes = existing,
                        responseCode = response.code,
                        contentRangeHeader = response.header("Content-Range"),
                        contentLength = response.body.contentLength(),
                    )
                    if (plan == null) {
                        if (existing > 0L && !restartedAfterInvalidRange) {
                            updateFile.delete()
                            writeDownloadSession(session.copy(etag = null, lastModified = null, expectedBytes = 0L))
                            restartedAfterInvalidRange = true
                            continue
                        }
                        throw DamagedDownloadException("invalid HTTP range response ${response.code}")
                    }

                    val responseEtag = response.header("ETag")
                    val responseLastModified = response.header("Last-Modified")
                    val validatorChanged = plan.mode == UpdateWriteMode.APPEND && when {
                        session.etag != null && responseEtag != null -> session.etag != responseEtag
                        session.etag == null && session.lastModified != null && responseLastModified != null ->
                            session.lastModified != responseLastModified
                        else -> false
                    }
                    if (validatorChanged) {
                        if (restartedAfterInvalidRange) throw DamagedDownloadException("update validator changed")
                        updateFile.delete()
                        writeDownloadSession(session.copy(etag = null, lastModified = null, expectedBytes = 0L))
                        restartedAfterInvalidRange = true
                        continue
                    }

                    if (plan.mode == UpdateWriteMode.ALREADY_COMPLETE) return
                    val append = plan.mode == UpdateWriteMode.APPEND
                    val startingBytes = if (append) existing else 0L
                    val expected = plan.expectedTotalBytes ?: 0L
                    if (expected > 0L) {
                        val remaining = (expected - startingBytes).coerceAtLeast(0L)
                        val freeRequired = safeAdd(remaining, expected) // transfer remainder + installer copy
                        if (updateDir.usableSpace < freeRequired) throw NotEnoughSpaceException(freeRequired)
                    }
                    val updatedSession = session.copy(
                        etag = responseEtag ?: session.etag,
                        lastModified = responseLastModified ?: session.lastModified,
                        expectedBytes = expected,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                    writeDownloadSession(updatedSession)
                    var copied = startingBytes
                    response.body.byteStream().use { input ->
                        FileOutputStream(updateFile, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                if (expected > 0L) {
                                    val percent = (copied * 100L / expected).toInt().coerceIn(0, 100)
                                    _state.value = State.Downloading(percent)
                                    onProgress(percent)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (copied <= 0L) throw EmptyDownloadException()
                    if (expected > 0L && copied != expected) {
                        // Network interruption keeps the exact partial file and validators for retry.
                        throw IOException("truncated update: got $copied of $expected bytes")
                    }
                    return
                }
            } finally {
                cancellationWatcher.cancel()
            }
        }
    }

    /**
     * Refuses anything the system cannot read back as this app's package and verifies the signer
     * before opening the installer. PackageInstaller repeats the signature check, but doing it here
     * turns a generic "App not installed" into the app's useful damaged-update message.
     */
    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw DamagedDownloadException("unparseable APK (${apk.length()} bytes)")
        if (info.packageName != context.packageName) {
            throw DamagedDownloadException("wrong package ${info.packageName}")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val updateVersionCode = versionCodeOf(info)
        val installedVersionCode = versionCodeOf(installed)
        if (updateVersionCode <= installedVersionCode) {
            throw DamagedDownloadException(
                "non-upgrade versionCode $updateVersionCode <= $installedVersionCode",
            )
        }
        val updateSigners = signerDigests(info)
        val installedSigners = signerDigests(installed)
        if (updateSigners.isEmpty() || installedSigners.isEmpty() || updateSigners.intersect(installedSigners).isEmpty()) {
            throw DamagedDownloadException("APK signature does not match installed ZenPlay")
        }
    }

    private fun verifyDigest(apk: File, expectedSha256: String?) {
        if (expectedSha256 == null) return
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw DamagedDownloadException("SHA-256 mismatch")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /**
     * Installs through [PackageInstaller] rather than an `ACTION_VIEW` intent on a FileProvider URI.
     * Two reasons: the session reads the APK from our own storage (no cross-app URI grant to go wrong
     * on older TV installers), and its status broadcast carries the real reason a package was refused
     * — insufficient storage, a signature conflict, a bad file — instead of the system's silent
     * "App not installed".
     */
    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        UpdateInstallNotifications.cancel(context)
        UpdateUserInteractionTracker.clearInstallApproval()
        abandonStaleInstallSession(installer, force = true)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = try {
            installer.createSession(params)
        } catch (e: IOException) {
            throw InstallException(e)
        }
        // Mark before writing/committing. A hard kill at any later instruction can then be repaired
        // from the retained verified APK when the next Activity resumes.
        synchronized(installRecoveryGuard) {
            UpdateInstallRecoveryStore.markPending(context)
            installerCommitOwnedByProcess = true
        }
        sessionPreferences.edit()
            .putInt(INSTALL_SESSION_ID, sessionId)
            .putLong(INSTALL_SESSION_CREATED_AT, System.currentTimeMillis())
            .commit()
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, UpdateInstallStatusReceiver::class.java)
                    .setAction(INSTALL_STATUS_ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            clearInstallSessionId()
            throw InstallException(error)
        }
        // PackageInstaller owns a copy now, but retain ours until STATUS_SUCCESS. It is the durable
        // fallback when a vendor installer loses its pending-user-action UI across process death.
    }

    internal fun handleInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            // The user still has to approve the install — this is the system prompt, not a failure.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    clearInstallSessionId()
                    _state.value = State.Failed(Failure.Install)
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val sessionId = sessionPreferences.getInt(INSTALL_SESSION_ID, 0).coerceAtLeast(0)
                when (installApprovalRoute(UpdateUserInteractionTracker.isUserActive)) {
                    InstallApprovalRoute.OPEN_NOW -> runCatching { context.startActivity(confirm) }
                        .onFailure {
                            Log.w(TAG, "installer confirmation could not open directly", it)
                            UpdateUserInteractionTracker.deferInstallApproval(confirm)
                            UpdateInstallNotifications.show(context, confirm, sessionId)
                        }
                    InstallApprovalRoute.NOTIFY -> {
                        // The in-memory route remains available when Android 13 notification
                        // permission is denied or a Fire/TV user disabled this channel.
                        UpdateUserInteractionTracker.deferInstallApproval(confirm)
                        UpdateInstallNotifications.show(context, confirm, sessionId)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateUserInteractionTracker.clearInstallApproval()
                UpdateInstallNotifications.cancel(context)
                clearInstallSessionId()
                clearDownloadSession()
                Log.i(TAG, "update installed")
            }
            // Declining the system prompt is a choice, not an error — leave the dialog as it was.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                UpdateUserInteractionTracker.clearInstallApproval()
                UpdateInstallNotifications.cancel(context)
                clearInstallSessionId()
                Log.i(TAG, "install cancelled by user")
            }
            else -> {
                UpdateUserInteractionTracker.clearInstallApproval()
                UpdateInstallNotifications.cancel(context)
                clearInstallSessionId()
                Log.w(TAG, "install refused status=$status message=$message")
                _state.value = State.Failed(Failure.InstallRejected(message?.takeIf { it.isNotBlank() }))
            }
        }
    }

    internal fun persistedProgress(): Int {
        val expected = readDownloadSession()?.expectedBytes ?: 0L
        return if (expected > 0L) {
            (updateFile.length() * 100L / expected).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    private fun prepareDownloadSession(info: UpdateInfo): DownloadSession {
        updateDir.mkdirs()
        val existing = readDownloadSession()
        if (existing != null && existing.matches(info) && updateFile.exists()) {
            val effectiveInfo = if (info.notes.isBlank()) info.copy(notes = existing.info.notes) else info
            val refreshed = existing.copy(info = effectiveInfo, updatedAtMs = System.currentTimeMillis())
            writeDownloadSession(refreshed)
            return refreshed
        }
        clearDownloadSession()
        return DownloadSession(info, null, null, 0L, System.currentTimeMillis()).also(::writeDownloadSession)
    }

    private fun DownloadSession.matches(info: UpdateInfo): Boolean =
        this.info.version == info.version && this.info.apkUrl == info.apkUrl && this.info.sha256 == info.sha256

    private fun discardSessionUnlessMatching(info: UpdateInfo) {
        if (readDownloadSession()?.matches(info) != true) clearDownloadSession()
    }

    private fun readDownloadSession(): DownloadSession? {
        val raw = sessionPreferences.getString(DOWNLOAD_SESSION, null) ?: return null
        return runCatching {
            val value = JSONObject(raw)
            val info = UpdateInfo(
                version = value.getString("version"),
                notes = value.optString("notes"),
                apkUrl = value.getString("url"),
                sha256 = value.optString("sha256").takeIf { it.isNotBlank() },
            )
            DownloadSession(
                info = info,
                etag = value.optString("etag").takeIf { it.isNotBlank() },
                lastModified = value.optString("lastModified").takeIf { it.isNotBlank() },
                expectedBytes = value.optLong("expectedBytes", 0L).coerceAtLeast(0L),
                updatedAtMs = value.optLong("updatedAt", 0L),
            )
        }.getOrElse {
            clearDownloadSession()
            null
        }
    }

    private fun writeDownloadSession(session: DownloadSession) {
        val value = JSONObject()
            .put("version", session.info.version)
            .put("notes", session.info.notes)
            .put("url", session.info.apkUrl)
            .put("sha256", session.info.sha256 ?: "")
            .put("etag", session.etag ?: "")
            .put("lastModified", session.lastModified ?: "")
            .put("expectedBytes", session.expectedBytes)
            .put("updatedAt", session.updatedAtMs)
        // Commit before bytes are appended: after a hard kill, file length and validator still agree.
        sessionPreferences.edit().putString(DOWNLOAD_SESSION, value.toString()).commit()
    }

    private fun clearDownloadMetadata(deleteFile: Boolean) {
        sessionPreferences.edit().remove(DOWNLOAD_SESSION).commit()
        if (deleteFile) updateFile.delete()
    }

    private fun clearDownloadSession() = clearDownloadMetadata(deleteFile = true)

    private fun cleanupStaleSession() {
        val session = readDownloadSession()
        if (session != null &&
            (System.currentTimeMillis() - session.updatedAtMs > DOWNLOAD_SESSION_MAX_AGE_MS ||
                !isNewer(session.info.version, currentVersion))
        ) {
            clearDownloadSession()
            abandonStaleInstallSession(context.packageManager.packageInstaller, force = true)
            return
        }
        abandonStaleInstallSession(context.packageManager.packageInstaller, force = false)
    }

    private fun abandonStaleInstallSession(installer: PackageInstaller, force: Boolean) {
        val id = sessionPreferences.getInt(INSTALL_SESSION_ID, -1)
        if (id < 0) return
        val createdAt = sessionPreferences.getLong(INSTALL_SESSION_CREATED_AT, 0L)
        if (!force && System.currentTimeMillis() - createdAt <= INSTALL_SESSION_MAX_AGE_MS) return
        runCatching { installer.abandonSession(id) }
        clearInstallSessionId()
    }

    private fun clearInstallSessionId() {
        synchronized(installRecoveryGuard) {
            UpdateInstallRecoveryStore.clear(context)
            installerCommitOwnedByProcess = false
        }
        sessionPreferences.edit()
            .remove(INSTALL_SESSION_ID)
            .remove(INSTALL_SESSION_CREATED_AT)
            .commit()
    }

    /** Retries the failed phase without losing a successfully resolved release asset. */
    fun retry() {
        val failed = _state.value as? State.Failed ?: return
        val info = failed.retryInfo
        if (info != null && failed.failure !is Failure.CheckHttp && failed.failure !is Failure.NoCompatibleApk &&
            failed.failure !is Failure.InvalidReleaseResponse && failed.failure !is Failure.CheckNetwork
        ) {
            _state.value = State.Available(info)
            downloadAndInstall()
        } else {
            check()
        }
    }

    fun reset() {
        if (_state.value !is State.Downloading) _state.value = State.Idle
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    /** Numeric segment-wise compare: "1.10.0" > "1.9.3"; non-numeric junk compares as 0. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private class InstallException(cause: Throwable) : IOException(cause)

    companion object {
        private const val TAG = "UpdateManager"
        private const val SESSION_ENTRY = "zenplay-update"
        private const val INSTALL_STATUS_ACTION = "tv.zenplay.universal.UPDATE_INSTALL_STATUS"
        private const val SESSION_PREFERENCES = "owntv_update_session"
        private const val DOWNLOAD_SESSION = "download_session"
        private const val INSTALL_SESSION_ID = "install_session_id"
        private const val INSTALL_SESSION_CREATED_AT = "install_session_created_at"
        private const val UPDATE_WORK_NAME = "owntv-apk-update"
        private const val DOWNLOAD_SESSION_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val INSTALL_SESSION_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

/** Selects one installable release APK without ever crossing an explicitly named update track. */
internal fun selectReleaseApk(
    assets: JSONArray,
    exactName: String,
    wantX86: Boolean,
): JSONObject? {
    val candidates = (0 until assets.length())
        .asSequence()
        .mapNotNull { assets.optJSONObject(it) }
        .filter { candidate ->
            val name = candidate.optString("name")
            val url = candidate.optString("browser_download_url")
            name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()
        }

    if (exactName.isNotBlank()) {
        return candidates.firstOrNull { candidate ->
            candidate.optString("name").equals(exactName, ignoreCase = true)
        }
    }

    return candidates.firstOrNull { candidate ->
        val isX86 = candidate.optString("name").contains("x86_64", ignoreCase = true)
        isX86 == wantX86
    }
}
