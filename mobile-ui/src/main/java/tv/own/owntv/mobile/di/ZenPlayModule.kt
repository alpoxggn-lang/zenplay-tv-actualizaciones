package tv.own.owntv.mobile.di

import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import tv.own.owntv.mobile.zenplay.ZenPlayAccount
import tv.own.owntv.mobile.zenplay.ZenPlayDetailsRepo
import tv.own.owntv.mobile.zenplay.ZenPlayEpg
import tv.own.owntv.mobile.zenplay.ZenPlayPlansViewModel

/** ZenPlay's additions: the automatic guide, the subscription expiry, and "Mis planes". */
val zenPlayModule = module {
    singleOf(::ZenPlayEpg)
    singleOf(::ZenPlayAccount)
    singleOf(::ZenPlayDetailsRepo)
    viewModelOf(::ZenPlayPlansViewModel)
}
