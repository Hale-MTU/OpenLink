package tech.mmarca.openlink

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * One setting: its numbers and when the user last changed it. Empty [values] means the user
 * cleared it, so a removal crosses too. A single number is a list of one.
 */
data class OpenLinkSetting(val values: List<Double>, val updatedAtMillis: Long) {
    constructor(value: Double, updatedAtMillis: Long) : this(listOf(value), updatedAtMillis)
}

/**
 * What a setting key may hold: [count] numbers, each in [min]..[max], ascending if
 * [ascending]. Numbers only: a setting is something the user chose (a goal, a limit), never a
 * reading, a name or free text, so the channel cannot carry such data under this capability.
 */
class OpenLinkSettingKey(
    val name: String,
    val count: Int,
    val min: Double,
    val max: Double,
    val ascending: Boolean = false,
) {
    /** Whether [values] fit this key. Empty always fits: the setting was cleared. */
    fun accepts(values: List<Double>): Boolean {
        if (values.isEmpty()) return true
        if (values.size != count) return false
        if (values.any { !it.isFinite() || it < min || it > max }) return false
        return !ascending || values.zipWithNext().all { (a, b) -> a < b }
    }

    override fun toString(): String = name
}

/**
 * The settings OpenLink carries (version 3, capability `settings`). A new one is a new entry
 * here and a row in docs/spec.md: no new method, no new protocol version and no new consent,
 * since it is the same kind of data the user already allowed. A side that does not know a key
 * drops it. Every key must be a choice the user made in one app that the other app shows or
 * uses; see "Settings" in docs/spec.md for what may never be one.
 */
object OpenLinkSettingKeys {
    val GOAL_STEPS = OpenLinkSettingKey("goal.steps", 1, 100.0, 100_000.0)
    val GOAL_ACTIVE_CALORIES_KCAL = OpenLinkSettingKey("goal.active_calories_kcal", 1, 10.0, 5_000.0)
    val GOAL_SLEEP_MINUTES = OpenLinkSettingKey("goal.sleep_minutes", 1, 60.0, 900.0)
    val GOAL_DISTANCE_METERS = OpenLinkSettingKey("goal.distance_meters", 1, 100.0, 100_000.0)
    val GOAL_FLOORS = OpenLinkSettingKey("goal.floors", 1, 1.0, 500.0)
    val GOAL_ELEVATION_METERS = OpenLinkSettingKey("goal.elevation_meters", 1, 1.0, 5_000.0)
    val GOAL_WATER_ML = OpenLinkSettingKey("goal.water_ml", 1, 100.0, 10_000.0)

    /** The user's own heart rate zone limits: the lowest bpm of zones 1 to 5. */
    val ZONES_LOWER_BPM = OpenLinkSettingKey("zones.lower_bpm", 5, 30.0, 250.0, ascending = true)

    val ALL: Map<String, OpenLinkSettingKey> = listOf(
        GOAL_STEPS,
        GOAL_ACTIVE_CALORIES_KCAL,
        GOAL_SLEEP_MINUTES,
        GOAL_DISTANCE_METERS,
        GOAL_FLOORS,
        GOAL_ELEVATION_METERS,
        GOAL_WATER_ML,
        ZONES_LOWER_BPM,
    ).associateBy { it.name }
}

/**
 * Settings by key, each with the time it last changed. The newer value of a key wins
 * ([merge]), key by key, so a goal changed in one app and a limit changed in the other both
 * survive. A sender may send only the keys it changed.
 *
 * What crosses the binder is checked with [valid] on both sides before anything uses it.
 */
data class OpenLinkSettings(val entries: Map<String, OpenLinkSetting> = emptyMap()) : Parcelable {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The single number of [key]; null if unset, cleared or not a single number. */
    fun number(key: OpenLinkSettingKey): Double? = entries[key.name]?.values?.singleOrNull()

    /** The numbers of [key]; null if unset or cleared. */
    fun numbers(key: OpenLinkSettingKey): List<Double>? = entries[key.name]?.values?.takeIf { it.isNotEmpty() }

    /** These settings with [key] set to [values] (empty clears it) as of [atMillis]. */
    fun with(key: OpenLinkSettingKey, values: List<Double>, atMillis: Long): OpenLinkSettings =
        OpenLinkSettings(entries + (key.name to OpenLinkSetting(values, atMillis)))

    /**
     * Only what may cross: keys OpenLink knows, values that fit them, and a time that is set and
     * not more than a day ahead of [nowMillis]. A time far ahead would pin a value the other side
     * could never replace.
     */
    fun valid(nowMillis: Long): OpenLinkSettings = OpenLinkSettings(
        entries.filter { (name, setting) ->
            val key = OpenLinkSettingKeys.ALL[name] ?: return@filter false
            setting.updatedAtMillis > 0L &&
                setting.updatedAtMillis <= nowMillis + MAX_CLOCK_AHEAD_MILLIS &&
                key.accepts(setting.values)
        },
    )

    /** These settings with every key of [other] that is newer here; a tie keeps this side's. */
    fun merge(other: OpenLinkSettings): OpenLinkSettings {
        val merged = entries.toMutableMap()
        for ((name, setting) in other.entries) {
            val mine = merged[name]
            if (mine == null || setting.updatedAtMillis > mine.updatedAtMillis) merged[name] = setting
        }
        return OpenLinkSettings(merged)
    }

    fun toBundle(): Bundle = Bundle().apply {
        for ((name, setting) in entries) {
            putBundle(
                name,
                Bundle().apply {
                    putDoubleArray(KEY_VALUES, setting.values.toDoubleArray())
                    putLong(KEY_UPDATED_AT, setting.updatedAtMillis)
                },
            )
        }
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeFields(toBundle())

    companion object {
        private const val KEY_VALUES = "values"
        private const val KEY_UPDATED_AT = "updatedAtMillis"

        /** How far ahead of the receiver's clock a change may be dated: phones' clocks differ a little. */
        const val MAX_CLOCK_AHEAD_MILLIS: Long = 24 * 60 * 60 * 1000L

        val EMPTY = OpenLinkSettings()

        /** Reads only the keys OpenLink knows; [valid] still checks their values. */
        fun fromBundle(bundle: Bundle): OpenLinkSettings = OpenLinkSettings(
            OpenLinkSettingKeys.ALL.keys.mapNotNull { name ->
                val entry = bundle.getBundle(name) ?: return@mapNotNull null
                val values = entry.getDoubleArray(KEY_VALUES) ?: return@mapNotNull null
                name to OpenLinkSetting(values.toList(), entry.getLong(KEY_UPDATED_AT, 0L))
            }.toMap(),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<OpenLinkSettings> = parcelableCreator(::fromBundle) { arrayOfNulls(it) }
    }
}
