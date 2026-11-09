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
        val signers = signers(packageName) ?: return null
        return OpenLinkCaller(uid, packageName, signers.current, signers.past)
    }

    private class Signers(val current: String, val past: Set<String>)

    @Suppress("DEPRECATION")
    private fun signers(packageName: String): Signers? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // No key rotation before API 28: the signatures are the signer set.
            val signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            return signerSet(signatures ?: return null)?.let { Signers(it, emptySet()) }
        }
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        if (signing.hasMultipleSigners()) {
            // Several signers cannot rotate; every one of them is part of the identity.
            return signerSet(signing.apkContentsSigners ?: return null)?.let { Signers(it, emptySet()) }
        }
        // One signer with its rotation history, oldest first; the current signer is the last.
        val history = signing.signingCertificateHistory?.map { sha256Hex(it.toByteArray()) }.orEmpty()
        val current = history.lastOrNull() ?: return null
        Signers(current, history.dropLast(1).toSet())
    }.getOrNull()

    /** One certificate as its digest; several as their sorted digests joined by "+", so all must match. */
    private fun signerSet(signatures: Array<Signature>): String? =
        signatures.map { sha256Hex(it.toByteArray()) }.sorted().takeIf { it.isNotEmpty() }?.joinToString("+")

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
