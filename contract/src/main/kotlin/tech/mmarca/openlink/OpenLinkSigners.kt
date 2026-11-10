package tech.mmarca.openlink

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Who signed an installed package, as the package manager says: the hex SHA-256 of its
 * current signing certificate, and of the certificates that key was rotated from (APK
 * signature scheme v3, API 28 and newer). An app signed by several certificates has no
 * rotation; its identity is all of them, as their sorted digests joined by "+".
 *
 * Both sides of the link identify the other by this: the host its callers, the client its host.
 */
data class OpenLinkSigners(
    val current: String,
    /** Oldest first, without [current]. The platform proves this lineage. */
    val past: Set<String> = emptySet(),
) {
    /** Whether the package carries [sha256]: as its current key, or as one it was rotated from. */
    fun carries(sha256: String): Boolean = normalize(sha256).let { it == current || it in past }

    /** Whether the package carries any of [sha256s]. */
    fun carriesAny(sha256s: Set<String>): Boolean = sha256s.any(::carries)

    companion object {
        /** The signers of [packageName], or null if it is not installed, not visible, or unsigned. */
        @Suppress("DEPRECATION")
        fun of(packageManager: PackageManager, packageName: String): OpenLinkSigners? = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // No key rotation before API 28: the signatures are the signer set.
                val signatures = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
                return signerSet(signatures ?: return null)?.let { OpenLinkSigners(it) }
            }
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                // Several signers cannot rotate; every one of them is part of the identity.
                return signerSet(signing.apkContentsSigners ?: return null)?.let { OpenLinkSigners(it) }
            }
            // One signer with its rotation history, oldest first; the current signer is the last.
            val history = signing.signingCertificateHistory?.map { sha256Hex(it.toByteArray()) }.orEmpty()
            val current = history.lastOrNull() ?: return null
            OpenLinkSigners(current, history.dropLast(1).toSet())
        }.getOrNull()

        /** One certificate as its digest; several as their sorted digests joined by "+", so all must match. */
        private fun signerSet(signatures: Array<Signature>): String? =
            signatures.map { sha256Hex(it.toByteArray()) }.sorted().takeIf { it.isNotEmpty() }?.joinToString("+")

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A digest as this library compares it: lowercase hex, no colons. */
        fun normalize(sha256: String): String = sha256.replace(":", "").trim().lowercase()
    }
}
