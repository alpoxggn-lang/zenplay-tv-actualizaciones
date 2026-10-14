package tv.own.owntv.mobile.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.mobile.R
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.settings.StartupMode
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.core.theme.ThemeMode

/** Credentials live only in this ViewModel until the existing source store saves a valid account. */
class ZenPlayLoginViewModel(
    private val authenticator: ZenPlayAuthenticator,
    private val importer: SourceImporter,
    private val profiles: ProfileDao,
    private val sources: SourceRepository,
    private val settings: SettingsRepository,
    private val epg: tv.own.owntv.mobile.zenplay.ZenPlayEpg,
) : ViewModel() {
    var plan by mutableStateOf(ZenPlayPlan.ZENPLAY)
        private set
    var username by mutableStateOf("")
        private set
    // Intentionally not a SavedStateHandle/rememberSaveable value.
    var password by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var errorRes by mutableStateOf<Int?>(null)
        private set
    private val _completedProfileId = MutableStateFlow<Long?>(null)
    val completedProfileId = _completedProfileId.asStateFlow()
    val progress = importer.progress
    private var loginJob: Job? = null

    fun selectPlan(value: ZenPlayPlan) { if (!busy) { plan = value; errorRes = null } }
    fun updateUsername(value: String) { if (!busy) { username = value; errorRes = null } }
    fun updatePassword(value: String) { if (!busy) { password = value; errorRes = null } }
    fun consumeCompletion() { _completedProfileId.value = null }

    /** ZenPlay: checks the form before the profile step; false shows the "missing fields" message. */
    fun validate(): Boolean {
        if (busy) return false
        if (username.trim().isBlank() || password.isEmpty()) {
            errorRes = R.string.zenplay_login_missing_fields
            return false
        }
        return true
    }

    /**
     * Sign in and load the plan into a new profile. ZenPlay: the profile's name, picture, optional
     * PIN and kids flag come from the profile step shown after the account details.
     */
    fun signIn(profileName: String, avatarId: Int = 0, isKids: Boolean = false, pin: String? = null) {
        if (busy) return
        val user = username.trim()
        val secret = password // Leading/trailing spaces may be part of a valid password.
        if (user.isBlank() || secret.isEmpty()) {
            errorRes = R.string.zenplay_login_missing_fields
            return
        }
        val selectedPlan = plan
        busy = true
        importing = false
        errorRes = null
        loginJob = viewModelScope.launch {
            var newProfileId: Long? = null
            var newSource: tv.own.owntv.core.database.entity.SourceEntity? = null
            var activated = false
            try {
                val result = authenticator.authenticate(selectedPlan.serverUrl, user, secret)
                currentCoroutineContext().ensureActive()
                if (result != ZenPlayAuthResult.ACTIVE) {
                    errorRes = when (result) {
                        ZenPlayAuthResult.INVALID_CREDENTIALS -> R.string.zenplay_login_invalid
                        ZenPlayAuthResult.EXPIRED -> R.string.zenplay_login_expired
                        ZenPlayAuthResult.UNREACHABLE -> R.string.zenplay_login_unreachable
                        else -> R.string.zenplay_login_invalid_response
                    }
                    return@launch
                }
                // An inactive profile cannot route MainActivity into the application. Activation is
                // deliberately the LAST step, after the server accepts the account and import succeeds.
                importer.reset()
                importer.backgroundHandoff = false
                importer.useProfile(-1L)
                val createdProfileId = withContext(NonCancellable) {
                    importer.createProfile(profileName, avatarId = avatarId.coerceAtLeast(0), isKids = isKids, pin = pin)
                        .also { newProfileId = it }
                }
                currentCoroutineContext().ensureActive()
                importing = true
                importer.xtream(
                    profileName, selectedPlan.serverUrl, user, secret,
                    autoRefresh = PlaylistRefresh(PlaylistAutoRefresh.STARTUP),
                )
                val resultState = importer.state.value as? SourceImporter.ImportState.Success
                newSource = resultState?.source
                currentCoroutineContext().ensureActive()
                if (resultState == null || newSource == null) {
                    errorRes = R.string.zenplay_login_import_failed
                    return@launch
                }
                settings.setAccent(AccentColor.BLUE)
                settings.setThemeMode(ThemeMode.DARK)
                settings.setStartupMode(createdProfileId, StartupMode.HOME)
                // ZenPlay: the plan's TV guide is added and downloaded in the background.
                runCatching { epg.ensureFor(newSource!!) }
                // Once activation starts, cancellation must not delete the now-active profile.
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val profileId = importer.finish()
                    check(profileId == createdProfileId)
                    activated = true
                    password = ""
                    _completedProfileId.value = profileId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Raw exceptions can contain a URL with account credentials. Only show a local message.
                errorRes = R.string.zenplay_login_import_failed
            } finally {
                if (!activated) {
                    withContext(NonCancellable) {
                        newSource?.let { source -> runCatching { sources.deleteSource(source) } }
                        newProfileId?.let { id ->
                            runCatching { profiles.getById(id)?.let { profiles.delete(it) } }
                        }
                    }
                    importer.useProfile(-1L)
                    importer.reset()
                }
                importing = false
                busy = false
            }
        }
    }

    /** Cleanup finishes before the form becomes usable again, preventing overlapping imports. */
    fun cancel() { loginJob?.cancel() }
}
