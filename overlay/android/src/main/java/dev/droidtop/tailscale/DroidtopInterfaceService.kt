package dev.droidtop.tailscale

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.notifier.Notifier
import dev.droidtop.pluginhost.IPluginHostBroker
import dev.droidtop.pluginhost.IPluginRuntime
import dev.droidtop.pluginhost.IPluginRuntimeCallback
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tailscale as one of droidtop's plugins: an `app_interface` plugin, which is this app itself (droidtop
 * docs/plugin-api.md 1.3; Droidtop/tracker#645). The owner, 2026-11-05: "We're building a local tailscale control
 * plugin, after all. Build it as ab Application Interface plugin". droidtop binds this service with [ACTION] over
 * Droidtop/lib-plugins' AIDL and hands over a broker, the only way this app reaches droidtop.
 *
 * While droidtop holds it, the online peers' addresses go to droidtop's `peers.set_addresses` whenever they change,
 * and droidtop draws the facets ([Facets]). The service answers droidtop alone: the calling uid must be droidtop's
 * package, signed with a pinned certificate. Shaped as Enginehost's DroidtopInterfaceService.
 */
class DroidtopInterfaceService : Service() {
    override fun onBind(intent: Intent): IBinder? = if (intent.action == ACTION) Runtime(applicationContext) else null

    private class Runtime(private val context: Context) : IPluginRuntime.Stub() {
        private fun caller(): Boolean = isDroidtop(context, Binder.getCallingUid()).also {
            if (!it) Log.w(TAG, "refused a call from uid ${Binder.getCallingUid()}: not droidtop")
        }

        override fun registerCallback(callback: IPluginRuntimeCallback?) {}
        override fun unregisterCallback(callback: IPluginRuntimeCallback?) {}
        override fun loadPlugin(pluginId: String?, installDir: String?, entryClass: String?, rootApproved: Boolean, broker: IPluginHostBroker?) = false

        override fun loadContained(
            pluginId: String?,
            manifestJson: String?,
            files: Array<out ParcelFileDescriptor>?,
            names: Array<out String>?,
            broker: IPluginHostBroker?,
        ): Boolean {
            files?.forEach { runCatching { it.close() } }
            if (!caller() || pluginId == null || broker == null) return false
            Link.connected(pluginId, broker)
            return true
        }

        override fun reachability(): String? = null
        override fun attachScreen(
            pluginId: String?, entrypoint: String?, library: String?, surface: Surface?,
            frames: ParcelFileDescriptor?, framesCapacity: Long, width: Int, height: Int, density: Float,
        ): String? = null
        override fun resizeScreen(pluginId: String?, width: Int, height: Int) {}
        override fun inputToken(): Long = 0L
        override fun screenTouch(token: Long, pluginId: String?, event: MotionEvent?) {}
        override fun screenKey(token: Long, pluginId: String?, event: KeyEvent?) {}
        override fun detachScreen(pluginId: String?) {}
        override fun lastLoadError(pluginId: String?): String? = null

        override fun unloadPlugin(pluginId: String?) {
            if (caller() && pluginId != null) Link.disconnected(pluginId)
        }

        override fun invoke(pluginId: String?, capability: String?, argsJson: String?): String =
            error("UNSUPPORTED", "Tailscale answers only through handle")

        override fun handle(pluginId: String?, envelopeJson: String?): String {
            if (!caller()) return error("PERMISSION_DENIED", "Only droidtop may call Tailscale here")
            val envelope = runCatching { JSONObject(envelopeJson ?: "") }.getOrNull() ?: return error("INVALID_ARGS", "The call was not readable")
            val point = envelope.optString("point")
            val op = envelope.optString("op")
            val args = envelope.optJSONObject("args") ?: JSONObject()
            if (point != "ui.facets") return error("UNSUPPORTED", "Tailscale does not provide $point")
            return try {
                when (op) {
                    "get" -> ok(JSONObject().put("facets", facets(args.optString("target"))))
                    Facets.SET_ON -> setOn(args.optBoolean("value"), args.optJSONObject("context")?.optString("target") ?: "plugin.page")
                    else -> error("UNSUPPORTED", "Tailscale does not answer $op")
                }
            } catch (e: Exception) {
                Log.w(TAG, "handle $point $op failed", e)
                error("FAILED", "Tailscale could not answer")
            }
        }

        private fun facets(target: String): JSONArray =
            Facets.get(target, Notifier.state.value, Notifier.netmap.value, UninitializedApp.get().isAbleToStartVPN())

        private fun setOn(on: Boolean, target: String): String {
            val app = UninitializedApp.get()
            if (on && !app.isAbleToStartVPN()) return error("FAILED", "Open the Tailscale app once to allow its VPN")
            if (on) app.startVPN() else app.stopVPN()
            return ok(JSONObject().put("facets", facets(target)))
        }

        override fun startJob(pluginId: String?, capability: String?, argsJson: String?, jobId: String?) = false
        override fun cancelJob(pluginId: String?, jobId: String?) {}
        override fun attachRelay(pluginId: String?, name: String?, relay: IBinder?) {}
    }

    /** The broker droidtop handed over, while it is loaded, and the job that feeds it the tailnet's addresses. */
    internal object Link {
        @Volatile private var broker: IPluginHostBroker? = null
        @Volatile private var pluginId: String? = null
        private var feeding: Job? = null

        @Synchronized
        fun connected(id: String, given: IPluginHostBroker) {
            pluginId = id
            broker = given
            feeding?.cancel()
            // Starting libtailscale is what starts the Notifier; the app does it on its own first screen too.
            val app = App.get()
            feeding = app.applicationScope.launch {
                runCatching { app.getLibtailscaleApp() }
                Notifier.state.combine(Notifier.netmap) { state, netmap -> TailnetPeers.addresses(state, netmap) }
                    .distinctUntilChanged()
                    .collect { addresses -> call("peers", "set_addresses", JSONObject().put("addresses", JSONArray(addresses))) }
            }
        }

        @Synchronized
        fun disconnected(id: String) {
            if (pluginId != id) return
            feeding?.cancel()
            feeding = null
            broker = null
            pluginId = null
        }

        /** One broker call, `{api, version, op, args}`; the parsed reply, or null with no broker or no answer. */
        fun call(api: String, op: String, args: JSONObject, version: Int = 1): JSONObject? {
            val current = broker ?: return null
            val request = JSONObject().put("api", api).put("version", version).put("op", op).put("args", args)
            return runCatching { JSONObject(current.call(request.toString())) }.getOrElse {
                broker = null // droidtop went away; it binds again when it comes back
                null
            }
        }
    }

    companion object {
        const val ACTION = "dev.droidtop.plugin.APP_INTERFACE"
        private const val TAG = "DroidtopTailscale"
        const val DROIDTOP_PACKAGE = "dev.droidtop.app"

        /**
         * SHA-256 of droidtop's APK signing certificate, the only signer answered: droidtop's release key, as
         * Enginehost pins it (its DroidtopLink.DROIDTOP_CERTS, read from droidtop.apk v0.2.0-dev.2257).
         */
        internal val DROIDTOP_CERTS: Set<String> = setOf("38b1ef4bc47fa8cd62ffcbb43fa85d7f450e9c2d4a8b690776d2218b23f0749f")

        internal fun ok(data: JSONObject): String = JSONObject().put("ok", true).put("data", data).toString()
        internal fun error(code: String, message: String): String =
            JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message)).toString()

        fun isDroidtop(context: Context, uid: Int): Boolean {
            val packages = context.packageManager.getPackagesForUid(uid) ?: return false
            if (DROIDTOP_PACKAGE !in packages) return false
            val signers = signerDigests(context.packageManager, DROIDTOP_PACKAGE)
            return signers.isNotEmpty() && signers.all { it in DROIDTOP_CERTS }
        }

        @Suppress("DEPRECATION")
        private fun signerDigests(pm: PackageManager, packageName: String): Set<String> = runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    ?: return@runCatching emptySet<String>()
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory.takeLast(1).toTypedArray()
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            signatures.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }.getOrDefault(emptySet())
    }
}
