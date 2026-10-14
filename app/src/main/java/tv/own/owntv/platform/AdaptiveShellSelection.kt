package tv.own.owntv.platform

/** Exactly one top-level shell is mounted for a capability profile. */
enum class ZenShell {
    REMOTE,
    TOUCH,
}

val AdaptiveDeviceProfile.shell: ZenShell
    get() = when (navigation) {
        ZenNavigation.REMOTE -> ZenShell.REMOTE
        ZenNavigation.BOTTOM_BAR,
        ZenNavigation.RAIL,
        -> ZenShell.TOUCH
    }

