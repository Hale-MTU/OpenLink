package tech.mmarca.openlink.host

import android.app.Service
import android.content.Intent
import android.os.IBinder
import tech.mmarca.openlink.OpenLink

/**
 * The exported service. The host app subclasses it, supplies its [OpenLinkHost] singleton,
 * and declares it in the manifest behind its OpenLink permission, with an intent filter for
 * [OpenLink.ACTION_HOST]:
 *
 * ```xml
 * <service android:name=".link.OpenWatchLinkService" android:exported="true"
 *     android:permission="tech.mmarca.openwatch.permission.OPENLINK">
 *     <intent-filter><action android:name="tech.mmarca.openlink.action.HOST" /></intent-filter>
 * </service>
 * ```
 */
abstract class OpenLinkHostService : Service() {
    protected abstract val host: OpenLinkHost

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == OpenLink.ACTION_HOST) host.binder else null
}
