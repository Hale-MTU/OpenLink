package tech.mmarca.openlink.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import tech.mmarca.openlink.OpenLink

/**
 * The exported service. The host app subclasses it, supplies its [OpenLinkHost] singleton,
 * and declares it exported with an intent filter for [OpenLink.ACTION_HOST]:
 *
 * ```xml
 * <service android:name=".link.OpenWatchLinkService" android:exported="true">
 *     <intent-filter><action android:name="tech.mmarca.openlink.action.HOST" /></intent-filter>
 * </service>
 * ```
 *
 * No `android:permission` on it: who may do what is the trust store's decision, per call,
 * from the caller's signing certificate (docs/spec.md, "Identity and permission").
 */
abstract class OpenLinkHostService : Service() {
    protected abstract val host: OpenLinkHost

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == OpenLink.ACTION_HOST) host.binder else null

    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action == OpenLink.ACTION_HOST) host.onAllUnbound()
        // Ask for onRebind, so the next client's arrival is seen too; the binder is the same.
        return true
    }

    override fun onRebind(intent: Intent?) = Unit
}
