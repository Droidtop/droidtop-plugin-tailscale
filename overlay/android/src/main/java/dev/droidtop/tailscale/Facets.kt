package dev.droidtop.tailscale

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin's facets (docs/plugin-api.md, `ui.facets`): its page and its Quick Menu entry, drawn by droidtop. The
 * state, a switch, and on its page this device's tailnet address and the devices on the tailnet. Signing in happens
 * in the app itself, which droidtop opens; the facets say so.
 */
internal object Facets {
    const val SET_ON = "set_on"

    fun get(target: String, state: Ipn.State, netmap: Netmap.NetworkMap?, ableToStart: Boolean): JSONArray {
        val facets = JSONArray().put(status(state)).put(toggle(state))
        if (state == Ipn.State.NeedsLogin || state == Ipn.State.NeedsMachineAuth || !ableToStart) {
            facets.put(
                JSONObject().put("kind", "notice").put("id", "tailscale_sign_in").put("tone", "info")
                    .put("title", if (!ableToStart) "Open Tailscale once to allow its VPN" else "Sign in in the Tailscale app")
                    .put("sub", "Android asks for each VPN once, in the app"),
            )
        }
        if (target == "plugin.page" && state == Ipn.State.Running && netmap != null) {
            netmap.SelfNode.Addresses.orEmpty().firstOrNull()?.let {
                facets.put(JSONObject().put("kind", "info").put("id", "tailscale_me").put("label", "This device").put("value", it.substringBefore('/')))
            }
            facets.put(devices(netmap))
        }
        return facets
    }

    private fun status(state: Ipn.State): JSONObject {
        val (s, words) = when (state) {
            Ipn.State.Running -> "on" to "Connected"
            Ipn.State.Starting -> "busy" to "Connecting"
            Ipn.State.Stopping -> "busy" to "Disconnecting"
            Ipn.State.Stopped -> "off" to "Off"
            Ipn.State.NeedsLogin, Ipn.State.NeedsMachineAuth -> "warning" to "Needs signing in"
            else -> "unknown" to "Not started"
        }
        return JSONObject().put("kind", "status").put("id", "tailscale_status").put("label", "Tailscale").put("state", s).put("value", words)
    }

    private fun toggle(state: Ipn.State): JSONObject =
        JSONObject().put("kind", "toggle").put("id", "tailscale_on").put("label", "Connected to the tailnet")
            .put("value", state == Ipn.State.Running || state == Ipn.State.Starting)
            .put("call", JSONObject().put("op", SET_ON))

    private fun devices(netmap: Netmap.NetworkMap): JSONObject {
        val entries = JSONArray()
        netmap.Peers.orEmpty().sortedWith(compareBy({ it.Online != true }, { it.displayName.lowercase() })).take(200).forEach { node ->
            entries.put(
                JSONObject().put("id", node.StableID).put("title", node.displayName)
                    .put("sub", node.Addresses.orEmpty().firstOrNull()?.substringBefore('/') ?: "")
                    .put("value", if (node.Online == true) "Online" else "Offline"),
            )
        }
        return JSONObject().put("kind", "list").put("id", "tailscale_devices").put("title", "Devices").put("entries", entries)
    }
}
