package tech.mmarca.openlink.host

import android.content.Context
import android.content.SharedPreferences
import tech.mmarca.openlink.OpenLinkSigners

/**
 * Resolves the calling uid to one package and its signing certificate. A uid shared by
 * several packages is refused: the trust is given to an app, and such a uid is not one.
 */
class OpenLinkCallerResolver(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    fun resolve(uid: Int): OpenLinkCaller? {
        val packages = packageManager.getPackagesForUid(uid)?.distinct().orEmpty()
        if (packages.size != 1) return null
        val packageName = packages.single()
        val signers = OpenLinkSigners.of(packageManager, packageName) ?: return null
        return OpenLinkCaller(uid, packageName, signers.current, signers.past)
    }
}

/** The trust records in the host app's private preferences. */
class SharedPreferencesTrustStorage(private val prefs: SharedPreferences) : OpenLinkTrustStorage {
    override fun read(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS_NAME = "openlink_trust"

        fun create(context: Context): SharedPreferencesTrustStorage =
            SharedPreferencesTrustStorage(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
