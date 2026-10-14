package tv.own.owntv.core.database.entity

import java.util.UUID

/** Canonical UUIDs used to address a profile outside this device's local Room database. */
object ProfileSyncId {
    fun newId(): String = UUID.randomUUID().toString()

    fun isValid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}
