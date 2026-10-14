package tv.own.owntv.mobile

/** Values supplied by whichever application hosts the reusable touch UI. */
object MobileHostInfo {
    @Volatile var versionName: String = BuildConfig.VERSION_NAME
    @Volatile var devTools: Boolean = BuildConfig.DEV_TOOLS
}

