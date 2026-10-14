package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.core.live.LiveArchiveUrls
import tv.own.owntv.mobile.cast.CastController
import tv.own.owntv.mobile.playback.DataSaverGate
import tv.own.owntv.mobile.playback.PipController
import tv.own.owntv.mobile.ui.player.SubtitleSearchViewModel
import tv.own.owntv.mobile.ui.screens.guide.GuideViewModel
import tv.own.owntv.mobile.ui.screens.home.HomeViewModel
import tv.own.owntv.mobile.ui.screens.live.ChannelDetailViewModel
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.LiveViewModel
import tv.own.owntv.mobile.ui.screens.search.SearchViewModel

/**
 * Mobile-only bindings used when the universal host selects the touch shell.
 *
 * The host already owns Core, databases, the primary player, Exo live engine, engine pool,
 * PlaybackSession, SubtitleController and the shared catalogue readers. Re-registering any of
 * those would silently replace the instances the TV shell uses when a keyboard/remote changes the
 * capability profile. This list therefore contains only mobile-package types plus LiveArchiveUrls,
 * which the TV implementation constructs privately and never registers in Koin.
 */
val universalHostModules: List<Module> = listOf(
    shellModule,
    libraryModule,
    settingsModule,
    downloadsModule,
    zenPlayModule,
    module {
        single { PipController() }
        single { DataSaverGate(context = androidContext(), settings = get(), localeStore = get()) }
        single { CastController(context = androidContext(), session = get()) }
        viewModelOf(::SubtitleSearchViewModel)
    },
    module {
        single { LiveArchiveUrls(get(), get(), get(), get()) }
        single {
            LiveTuner(
                context = androidContext(),
                channelDao = get(),
                categoryDao = get(),
                historyDao = get(),
                profileDao = get(),
                favoriteDao = get(),
                sourceDao = get(),
                settings = get(),
                customize = get(),
                streamUrlResolver = get(),
                epgReader = get(),
                archiveUrls = get(),
                session = get(),
                dataSaver = get(),
                audioOnlyStore = get(),
                userDataWriter = get(),
                cast = get(),
                recordings = get(),
                exo = get(),
                forceMpvStore = get(),
                player = get(),
                watchSession = get(),
            )
        }
        viewModelOf(::LiveViewModel)
        viewModelOf(::ChannelDetailViewModel)
        viewModelOf(::GuideViewModel)
        viewModelOf(::HomeViewModel)
        viewModelOf(::SearchViewModel)
    },
)

