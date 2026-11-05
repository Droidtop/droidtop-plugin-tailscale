package dev.droidtop.tailscale

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetPeersTest {
    private fun node(name: String, online: Boolean?, vararg addresses: String) =
        Tailcfg.Node(StableID = name, Name = name, Online = online, Addresses = addresses.toList())

    private val netmap = Netmap.NetworkMap(
        SelfNode = node("me", true, "100.64.0.1/32"),
        Peers = listOf(
            node("phone", true, "fd7a:115c:a1e0::2/128", "100.64.0.2/32"),
            node("old-laptop", false, "100.64.0.3/32"),
            node("pc", null, "100.64.0.4/32"),
        ),
        Domain = "example.ts.net",
        UserProfiles = emptyMap(),
        TKAEnabled = false,
    )

    @Test
    fun onlinePeersOnlyIpv4FirstWithoutPrefixes() {
        assertEquals(listOf("100.64.0.2", "fd7a:115c:a1e0::2"), TailnetPeers.addresses(Ipn.State.Running, netmap))
    }

    @Test
    fun nothingUnlessRunning() {
        assertEquals(emptyList<String>(), TailnetPeers.addresses(Ipn.State.Stopped, netmap))
        assertEquals(emptyList<String>(), TailnetPeers.addresses(Ipn.State.NeedsLogin, netmap))
        assertEquals(emptyList<String>(), TailnetPeers.addresses(Ipn.State.Running, null))
    }
}
