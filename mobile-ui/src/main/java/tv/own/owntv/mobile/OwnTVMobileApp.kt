package tv.own.owntv.mobile

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.util.Perf
import tv.own.owntv.core.di.coreModule
import tv.own.owntv.core.di.dataModule
import tv.own.owntv.core.di.databaseModule
import tv.own.owntv.core.i18n.AppLocale
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.sync.work.KoinWorkerFactory
import tv.own.owntv.core.util.CrashRecorder
import tv.own.owntv.mobile.di.downloadsModule
import tv.own.owntv.mobile.di.guideModule
import tv.own.owntv.mobile.di.homeModule
import tv.own.owntv.mobile.di.libraryModule
import tv.own.owntv.mobile.di.liveModule
import tv.own.owntv.mobile.di.playerModule
import tv.own.owntv.mobile.di.searchModule
import tv.own.owntv.mobile.di.settingsModule
import tv.own.owntv.mobile.di.shellModule
import tv.own.owntv.mobile.ui.setup.ZenPlayCatalogRefresh
import tv.own.owntv.mobile.ui.setup.ZenPlayForegroundGate
import tv.own.owntv.mobile.ui.setup.ZenPlayRefreshRequestResult
import tv.own.owntv.mobile.ui.theme.subtitleFontResource

/**
 * The mobile shell's Application. Deliberately a near-copy of the TV app's `OwnTVApp` for the parts
 * that core depends on — the hooks below are core's only way to learn things a library cannot know
 * about the app compiled around it, and core reads them from its very first line.
 */
class OwnTVMobileApp : Application(), androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory())
            .build()

    /**
     * Wrap the Application base with the selected locale and apply it to the process locale defaults
     * BEFORE the rest of the process starts. The tag is read synchronously from the
     * SharedPreferences-backed store, because DataStore cannot be read here.
     */
    override fun attachBaseContext(base: Context) {
        val tag = LocaleStore.from(base).readBlocking()
        AppLocale.applyGlobally(tag)
        super.attachBaseContext(AppLocale.wrap(base, tag))
    }

    /** Re-apply the selection after the framework's process-level reset on a configuration change. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.applyGlobally(LocaleStore.from(this).readBlocking())
    }

    override fun onCreate() {
        super.onCreate()
        // "Restart now" after an icon change runs a few milliseconds in a process of its own; nothing
        // below may start there (core's AppRestartActivity).
        if (tv.own.owntv.core.brand.AppIconSwitcher.isRestartProcess(this)) return
        // Zero-point for the OwnTVPerf startup timeline (adb logcat -s OwnTVPerf), matching the TV
        // app. Without it every Perf.stamp in core is a silent no-op — which mattered more than a
        // missing timeline: core's database-open stamp is the only thing that proves Room's onOpen
        // callback actually ran under a SQLiteDriver. That callback fails *silently* when it does
        // not (core's plan §5.3), taking the PRAGMAs and the schema self-heal with it, so the phone
        // had no way to detect the one failure mode that ships green.
        Perf.begin()
        // Core has its own BuildConfig and it carries none of this: a library gets no version at all,
        // and the edge key and the maintainer switch are the app's build inputs. Hand them over
        // before the first reader — CrashRecorder, two lines down.
        CoreBuildInfo.versionName = BuildConfig.VERSION_NAME
        CoreBuildInfo.versionCode = BuildConfig.VERSION_CODE
        CoreBuildInfo.edgeKey = BuildConfig.TMDB_EDGE_KEY
        CoreBuildInfo.devTools = BuildConfig.DEV_TOOLS
        CoreBuildInfo.debug = BuildConfig.DEBUG
        CoreBuildInfo.diagnosticBuild = BuildConfig.DIAGNOSTIC_BUILD
        // No LEANBACK_LAUNCHER entry, so a Watch Next row published from here would be a row nothing
        // could open. Without this, core's sync worker tried to write to the TV content provider
        // after every catalog sync — using the two permissions this app's manifest removes.
        CoreBuildInfo.tvHome = false
        // ZenPlay updates are distributed separately; an upstream APK has a different identity.
        // ZenPlay: in-app updates from ZenPlay's own GitHub releases (see ZenPlayUpdates).
        CoreBuildInfo.releaseRepo = tv.own.owntv.mobile.zenplay.ZenPlayUpdates.REPO
        // Restore the durable PIN-attempt buckets before profiles or settings can validate a PIN.
        tv.own.owntv.core.util.PinAttemptGuard.initialize(this)
        // Route installer confirmation through the resumed screen, or a user-tapped notification
        // when WorkManager completes while the phone/tablet is in the background.
        tv.own.owntv.core.update.UpdateUserInteractionTracker.initialize(this)
        // First thing after the context exists: a crash from here on leaves a trace on disk instead
        // of dying with the process, with the playback ring attached.
        CrashRecorder.diagnostics = { tv.own.owntv.player.LiveDiagnosticsLog.snapshot() }
        CrashRecorder.install(this)
        // Core learns a panel's session limit while syncing; the engine is what acts on it. Registered
        // before Koin so the very first source flow already reaches the player.
        tv.own.owntv.core.player.LiveSessionLimit.report =
            tv.own.owntv.player.LiveStreamQuirks::rememberSessionLimit
        // Which font file the engine hands to libass, so a subtitle is drawn in the face the user
        // chose. This used to be left unset, on the grounds that the app had no fonts of its own —
        // true when the line was written, and untrue since Phase 1 shipped all six families as
        // res/font files. The result was a Subtitle font setting that reached the app's own subtitle
        // layer and nothing else.
        tv.own.owntv.player.SubtitleFontAssets.resourceOf = { it.subtitleFontResource }
        // The eight icon colours are MainActivity + a suffix (see MainActivityColours.kt); core switches them.
        tv.own.owntv.core.brand.AppIconSwitcher.mainActivityClass = MainActivity::class.java.name
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.ERROR else Level.NONE)
            androidContext(this@OwnTVMobileApp)
            modules(
                coreModule, databaseModule, dataModule, playerModule, shellModule,
                liveModule, libraryModule, guideModule, homeModule, searchModule,
                downloadsModule, settingsModule, tv.own.owntv.mobile.di.zenPlayModule,
            )
        }
        tv.own.owntv.core.update.UpdateUserInteractionTracker.bindInstallRecovery {
            org.koin.core.context.GlobalContext.get().get<tv.own.owntv.core.update.UpdateManager>()
        }
        // ZenPlay: ExoPlayer first for films and episodes, Spanish first, and the TV guide on its own.
        tv.own.owntv.mobile.zenplay.ZenPlayDefaults.start(
            this, appScope, org.koin.core.context.GlobalContext.get().get(),
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (!activity.isInPictureInPictureMode && foreground.onStarted(activity)) refreshCatalogsOnForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                foreground.onStopped(activity, activity.isChangingConfigurations)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) {
                // Returning from PiP can resume without another onStart. Ordinary resumes are no-ops.
                if (!activity.isInPictureInPictureMode && foreground.onStarted(activity)) refreshCatalogsOnForeground()
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // A chosen icon colour ("Later", the first-run pick, a restore) reaches the launcher when the app
        // is next in the background, never while it is on screen.
        tv.own.owntv.core.brand.AppIconSwitcher.start(this, org.koin.core.context.GlobalContext.get().get())
        // Diagnostics switch, the persisted archive-decode quirk and the one-shot settings migrations.
        tv.own.owntv.player.PlaybackStartup.start(
            context = this,
            scope = appScope,
            settings = org.koin.core.context.GlobalContext.get().get(),
            archiveStore = org.koin.core.context.GlobalContext.get().get(),
        )
        // "Use this guide's logos": never started on the phone before, so the toggle did nothing here.
        // Reads no EPG data at all while no guide source has it on.
        tv.own.owntv.core.epg.EpgLogoStore.start(
            scope = appScope,
            settings = org.koin.core.context.GlobalContext.get().get(),
            epgDao = org.koin.core.context.GlobalContext.get().get(),
            customize = org.koin.core.context.GlobalContext.get().get(),
        )
    }

    /** Process-long scope for [tv.own.owntv.player.PlaybackStartup]; never cancelled. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val foreground = ZenPlayForegroundGate()

    internal fun onActivityEnteredPictureInPicture(activity: Activity) {
        foreground.onPictureInPicture(activity)
    }

    internal val catalogRefresh by lazy {
        val koin = org.koin.core.context.GlobalContext.get()
        ZenPlayCatalogRefresh(koin.get(), koin.get(), koin.get(), koin.get())
    }

    /** One request for each real app entry; worker progress belongs to the existing sync tracker. */
    private fun refreshCatalogsOnForeground() {
        appScope.launch {
            // ZenPlay: add any plan's missing TV guide, and refresh guides older than 12 hours.
            runCatching {
                org.koin.core.context.GlobalContext.get().get<tv.own.owntv.mobile.zenplay.ZenPlayEpg>().ensureAll()
            }
        }
        appScope.launch {
            when (catalogRefresh.refreshNow()) {
                ZenPlayRefreshRequestResult.FAILED, ZenPlayRefreshRequestResult.PARTIAL ->
                    android.util.Log.w("ZenPlayCatalogRefresh", "Some catalog refresh requests could not be queued")
                else -> Unit
            }
        }
    }

    /** Memory pressure reaches every engine; which levels count is core's call (see PlaybackEngines). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<tv.own.owntv.player.PlaybackEngines>()?.onTrimMemory(level)
        }
    }
}
