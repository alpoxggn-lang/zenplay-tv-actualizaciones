package tv.own.owntv.mobile.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import tv.own.owntv.mobile.ui.screens.downloads.DownloadsViewModel
import tv.own.owntv.mobile.ui.screens.recordings.RecordingsViewModel

/**
 * The Downloads and Recordings screens. Both queues are core's and already registered there — these
 * are only the view models. Protected film/episode downloads stay in ZenPlay's private storage;
 * Recordings still needs the context for its own file handling.
 */
val downloadsModule = module {
    viewModel { RecordingsViewModel(context = androidContext(), settings = get(), recordings = get(), vodTuner = get()) }
    viewModel {
        DownloadsViewModel(
            downloadDao = get(),
            movieDao = get(),
            seriesDao = get(),
            contentAccess = get(),
            settings = get(),
            downloadManager = get(),
            vodTuner = get(),
            itemResolver = get(),
        )
    }
}
