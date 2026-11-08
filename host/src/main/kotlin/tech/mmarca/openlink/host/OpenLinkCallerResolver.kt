package tech.mmarca.openlink.host

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

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
        val signer = signerSha256(packageName) ?: return null
        return OpenLinkCaller(uid, packageName, signer)
    }

    @Suppress("DEPRECATION")
    private fun signerSha256(packageName: String): String? = runCatching {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            // A rotated key keeps its history; the current signer is the last one.
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return null
        val current = if (signatures.size == 1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            signatures.firstOrNull()
        } else {
            signatures.lastOrNull()
        } ?: return null
        sha256Hex(current.toByteArray())
    }.getOrNull()

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
