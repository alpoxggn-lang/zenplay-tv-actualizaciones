package tv.own.owntv.core.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap

internal enum class InstallApprovalRoute { OPEN_NOW, NOTIFY }

internal fun installApprovalRoute(userIsActive: Boolean): InstallApprovalRoute =
    if (userIsActive) InstallApprovalRoute.OPEN_NOW else InstallApprovalRoute.NOTIFY

internal fun shouldRecoverInstallApproval(
    durableApprovalPending: Boolean,
    commitOwnedByThisProcess: Boolean,
    recoveryAlreadyRunning: Boolean,
): Boolean = durableApprovalPending && !commitOwnedByThisProcess && !recoveryAlreadyRunning

/**
 * A boolean is deliberately stored separately from PackageInstaller's session id. The confirmation
 * [android.content.Intent] contains framework parcelables and cannot safely be serialized, while
 * this marker survives a killed receiver process and tells the next foreground process to recommit
 * the already verified APK. Recommit produces a fresh, valid confirmation Intent.
 */
internal object UpdateInstallRecoveryStore {
    private const val PREFERENCES = "owntv_update_approval"
    private const val APPROVAL_PENDING = "approval_pending"

    fun markPending(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(APPROVAL_PENDING, true)
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(APPROVAL_PENDING)
            .commit()
    }

    fun isPending(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(APPROVAL_PENDING, false)
}

/**
 * PackageInstaller may report from a manifest receiver after the initiating screen has disappeared.
 * Android only permits opening its confirmation UI directly while the user is actively in the app;
 * otherwise the receiver must leave a notification that the user chooses to open.
 */
object UpdateUserInteractionTracker : Application.ActivityLifecycleCallbacks {
    private val resumedActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var pendingInstallApproval: android.content.Intent? = null
    private var recoverDurableApproval: (() -> Unit)? = null

    @Volatile
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            application.registerActivityLifecycleCallbacks(this)
            initialized = true
        }
    }

    /** Bound only after Koin is ready; normal resumes do not resolve UpdateManager or do IO. */
    fun bindInstallRecovery(managerProvider: () -> UpdateManager) {
        synchronized(this) {
            recoverDurableApproval = { managerProvider().recoverPendingInstallApproval() }
        }
    }

    val isUserActive: Boolean
        get() = synchronized(resumedActivities) { resumedActivities.isNotEmpty() }

    fun deferInstallApproval(intent: android.content.Intent) {
        synchronized(this) { pendingInstallApproval = android.content.Intent(intent) }
        // Close the tiny race where an Activity resumes between the manager's foreground check and
        // this store. Starting from a currently resumed Activity is user initiated and permitted.
        synchronized(resumedActivities) { resumedActivities.firstOrNull() }
            ?.let(::deliverPendingApproval)
    }

    fun clearInstallApproval() {
        synchronized(this) { pendingInstallApproval = null }
    }

    override fun onActivityResumed(activity: Activity) {
        synchronized(resumedActivities) { resumedActivities.add(activity) }
        val hadInMemoryApproval = synchronized(this) { pendingInstallApproval != null }
        deliverPendingApproval(activity)
        if (!hadInMemoryApproval && UpdateInstallRecoveryStore.isPending(activity)) {
            synchronized(this) { recoverDurableApproval }?.invoke()
        }
    }

    private fun deliverPendingApproval(activity: Activity) {
        val pending = synchronized(this) { pendingInstallApproval } ?: return
        runCatching { activity.startActivity(pending) }
            .onSuccess {
                synchronized(this) {
                    if (pendingInstallApproval === pending) pendingInstallApproval = null
                }
                UpdateInstallNotifications.cancel(activity)
            }
    }

    override fun onActivityPaused(activity: Activity) {
        synchronized(resumedActivities) { resumedActivities.remove(activity) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        synchronized(resumedActivities) { resumedActivities.remove(activity) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
