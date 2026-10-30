// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import engine.mihomo.MihomoProfileFactory
import engine.mihomo.MihomoTunDevice
import engine.proxy.toLocalProxyOptions
import engine.proxy.toLocalProxyOptionsOrNull
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.daemon.config.AsteriskdHevSocks5TunnelHelper
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.tun2SocksInternalProxyPortValue
import engine.vpn.TunOptions
import engine.vpn.toTunOptions
import utils.toTrimmedNonEmptyDistinctList

internal data class MihomoTunConfig(
    val device: String,
    val stack: String,
    val mtu: Int,
    val ipv4Address: String,
    val ipv6Address: String?,
)

internal fun RootConfigBuildContext.buildTunStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val rootStartConfig = buildRootStartConfig()
    val iptablesConfig = buildRootIptablesConfig().copy(
        externalInterfacePrefixes = appState.tunSharedNetworkInterfaces.toTrimmedNonEmptyDistinctList().filterNot { it == "lo" },
    )
    val ipv6DataPath = rootStartConfig.enableIpv6
    val tunConfig = rawConfig?.let { config ->
        val inbound = requireNotNull(config.tunInbound.value) {
            "Raw Mihomo configuration requires one compatible TUN inbound for ROOT TUN mode"
        }
        if (ipv6DataPath) {
            requireNotNull(inbound.ipv6Address) {
                "Raw Mihomo configuration requires an IPv6 TUN address when IPv6 is enabled"
            }
        }
        MihomoTunConfig(
            device = inbound.device,
            stack = inbound.stack,
            mtu = inbound.mtu,
            ipv4Address = inbound.ipv4Address,
            ipv6Address = inbound.ipv6Address,
        )
    } ?: appState.buildMihomoTunConfig(appState.toTunOptions())
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = rawConfig?.toLocalProxyOptionsOrNull() ?: appState.toLocalProxyOptions().takeIf { rawConfig == null },
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            // meow-rs only accepts a pre-opened TUN fd on Android, so ROOT
            // TUN is delivered through META's proven TUN2SOCKS helper:
            // hev-socks5-tunnel owns asterisk0 and forwards to meow's SOCKS
            // listener.
            mode = AsteriskdMode.Tun2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf(tunConfig.device),
            modeOptions = AsteriskdModeOptions(
                transparentPort = null,
                tunnelName = null,
            ),
            helper = AsteriskdHevSocks5TunnelHelper(
                executablePath = rootStartConfig.runtimePaths.hevSocks5TunnelExecutablePath,
                socksHost = "127.0.0.1",
                socksPort = appState.tun2SocksInternalProxyPortValue(),
                tunnelName = tunConfig.device,
                mtu = tunConfig.mtu,
                ipv4Address = tunConfig.ipv4Address.substringBefore('/'),
                ipv6Address = appState.toTunOptions().ipv6Address.address.takeIf {
                    rootStartConfig.enableIpv6 ||
                        (rootStartConfig.enableLocalDns && !rootStartConfig.enableRootIpv6Disabler)
                },
                multiQueue = true,
                tcpFastOpen = true,
            ),
        ),
    )
}

private fun AppState.buildMihomoTunConfig(tunOptions: TunOptions): MihomoTunConfig {
    return MihomoTunConfig(
        device = MihomoTunDevice,
        stack = MihomoProfileFactory.tunStack(this),
        mtu = tunOptions.mtu,
        ipv4Address = "${tunOptions.ipv4Address.address}/${tunOptions.ipv4Address.prefixLength}",
        ipv6Address = if (enableIpv6) {
            "${tunOptions.ipv6Address.address}/${tunOptions.ipv6Address.prefixLength}"
        } else {
            null
        },
    )
}
