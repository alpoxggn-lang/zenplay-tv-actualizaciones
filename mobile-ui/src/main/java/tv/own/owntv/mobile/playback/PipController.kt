package tv.own.owntv.mobile.playback

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The small hand-off the activity and the player screen share for Picture-in-Picture.
 *
 * Only the activity can enter PiP, and only the player screen knows whether the picture is what the
 * user is looking at — so the decision needs one small piece of state that outlives neither of them
 * alone. Playback facts stay in the player; this class carries only window state, capability and the
 * one-shot request that must cross that boundary.
 */
class PipController {

    /** The full screen player owns the display. Home pressed now means "keep watching", not "leave". */
    val playerOnScreen = MutableStateFlow(false)

    /** The app is running in the little window — no controls, no gestures, no bars. */
    val inPip = MutableStateFlow(false)

    /**
     * True only while the host activity is the touch shell and Android can actually open a system
     * Picture-in-Picture window. The activity owns this answer because it is the only layer that can
     * inspect both its manifest declaration and the device feature; the player merely decides
     * whether to draw the button.
     *
     * It deliberately defaults to false. A TV/Fire TV activity never enables it, so sharing the
     * player composable across form factors cannot accidentally add a touch-only PiP control to the
     * remote interface.
     */
    val manualEntryAvailable = MutableStateFlow(false)

    // Conflated so a tap made during the activity collector's first scheduling frame is retained,
    // while repeated taps can never queue multiple PiP transitions.
    private val _manualEntryRequests = Channel<Unit>(capacity = Channel.CONFLATED)

    /** One-shot requests consumed by the activity, which is the only object allowed to enter PiP. */
    val manualEntryRequests = _manualEntryRequests.receiveAsFlow()

    /**
     * Requests the same system PiP window used when Home is pressed.
     *
     * Returning false lets tests and future callers distinguish an unavailable device/setting from
     * a request that was accepted without reaching into activity internals.
     */
    fun requestManualEntry(): Boolean {
        if (!manualEntryAvailable.value) return false
        return _manualEntryRequests.trySend(Unit).isSuccess
    }

    /**
     * Where the picture is on screen, in window coordinates, while the player is showing it.
     *
     * Handed to the system as the little window's *source*, which is what turns entering PiP into
     * the picture shrinking into the corner instead of the whole screen cross-fading into it. Null
     * whenever nothing is drawing a picture, and the system simply falls back to the cross-fade.
     *
     * A plain field rather than a flow: it is read once, inside the call that enters PiP, and a
     * position that changed a frame ago is not worth a recomposition anywhere.
     */
    var videoBounds: android.graphics.Rect? = null

    /**
     * The playback notification was tapped: put the full screen player back on screen.
     *
     * A one-shot, cleared by the shell once it has navigated. It has to travel this way because the
     * two ends cannot reach each other: the notification arrives as an Intent, which only the activity
     * sees, and the player is a navigation destination, which only the shell can reach.
     */
    val openPlayerRequested = MutableStateFlow(false)
}
