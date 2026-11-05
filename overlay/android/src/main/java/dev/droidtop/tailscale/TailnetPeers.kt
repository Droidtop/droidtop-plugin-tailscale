package dev.droidtop.tailscale

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap

/**
 * What droidtop is told about the tailnet (Droidtop/tracker#645): the addresses of the online peers while this node
 * runs, for droidtop's `peers.set_addresses` (docs/plugin-api.md D22), which takes IP literals and never names.
 */
internal object TailnetPeers {
    /** Each online peer's addresses, IPv4 first, without their prefix lengths; none unless the node is Running. */
    fun addresses(state: Ipn.State, netmap: Netmap.NetworkMap?): List<String> {
        if (state != Ipn.State.Running || netmap == null) return emptyList()
        return netmap.Peers.orEmpty()
            .filter { it.Online == true }
            .flatMap { node -> node.Addresses.orEmpty().map { it.substringBefore('/') }.sortedBy { ':' in it } }
            .distinct()
            .take(MAX)
    }

    /** droidtop's cap per call (HostPeersApis.MAX). */
    const val MAX = 256
}
