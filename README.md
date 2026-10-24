# Throne for Android

Android GUI proxy utility, empowered by [Sing-box](https://github.com/SagerNet/sing-box), sharing its core and data model with [Throne](https://github.com/throneproj/Throne) on desktop.

Supports Android 7.0 (API 24) and newer, including Android TV.

Formerly NekoBox for Android: since v2.0.0 the app runs the same core as Throne desktop, so profiles, subscriptions, route profiles and `.thrbackup` backups move freely between the two.

Documentation: [throneproj.github.io](https://throneproj.github.io) · Issues: [GitHub Issues](https://github.com/throneproj/ThroneForAndroid/issues)

### Supporting Throne
If Throne is useful to you, you can support its development with [crypto](https://github.com/throneproj/Throne/blob/dev/.github/donate/Donate.md) (USDT, USDC, TRX or Monero) or through [GitHub Sponsors](https://github.com/sponsors/Mahdi-zarei).

### Note on upgrading from 1.x
Releases from v2.0.0 on are signed with a new key, so Android refuses to install them over a 1.x release (signature conflict). Uninstall the old release first, then import your subscriptions and profiles again: old local configurations are not migrated.

### GitHub Releases (APK)

[![GitHub All Releases](https://img.shields.io/github/downloads/throneproj/ThroneForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/throneproj/ThroneForAndroid/releases)

Each release has one APK per ABI: `Throne-<version>-arm64-v8a.apk` fits almost every current phone, `armeabi-v7a` older 32-bit devices and `x86_64` emulators and x86 Chromebooks. `SHA256SUMS` lists their checksums.

GitHub builds update themselves: the in-app updater checks the stable or pre-release channel, downloads the APK matching your installed ABI and verifies it before installing.

## Features

- Groups and subscriptions that work like Throne desktop: per-group subscription options (filters, clean-up, URL test after an update), front / landing proxy, HWID headers, scheduled updates with change reports
- Backups in the desktop's `.thrbackup` format, restorable on either side: save, share or sync over WebDAV
- Route profiles shared with desktop, preloaded from [routeprofiles](https://github.com/throneproj/routeprofiles), plus Android-only rule conditions: apps, Wi-Fi network (SSID / BSSID), network type and metered networks
- Auto Selector profiles that keep the best server of one or more groups ranked and switch automatically
- Cloudflare WARP: registration (WireGuard or MASQUE identity) and a built-in WARP mode for routing
- URL, IP / country and speed tests, with a bulk-test panel (live progress, latency histogram, fastest servers, sort / remove unavailable / connect to fastest)
- The sing-box dashboard built in (live traffic, connections, logs), already connected to the running core
- Xray configs with `geoip:` / `geosite:` rules: the data files are downloaded when a config first needs them
- JSON editor with sing-box schema checking, completion and formatting
- Home-screen widgets, a quick settings tile, and server switching from the notification
- Android TV: launcher banner, D-pad navigation, previous / next server

## Supported protocols

- SOCKS
- HTTP(S)
- Shadowsocks
- Trojan
- VMess
- VLESS
- TUIC
- Hysteria
- Hysteria2
- AnyTLS
- Mieru
- Snell
- NaïveProxy
- Juicity
- TrustTunnel
- ShadowTLS
- Wireguard
- AmneziaWG
- MASQUE
- SSH
- Xray VLESS
- OpenVPN/OpenConnect
- Custom Outbound (Both Sing-box and Xray)
- Custom Config (Both Sing-box and Xray)
- Chaining outbounds

## Subscription Formats

Various formats are supported, including share links, various JSON representation of Sing-box configs, and v2rayN link format as well as limited support for Shadowsocks and Clash formats. Only the outbounds (servers) of a subscription are imported; its routing rules are ignored.

Deeplinks are also supported, read the [documentation](https://throneproj.github.io/advanced/deeplinks/) for more information.

<details>
<summary>XHTTP extra example (TLS)</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "b.yourdomain.com",
			"alpn": "h2",
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<details>
<summary>XHTTP extra example (REALITY)</summary>

<pre><code class="language-json">
{
    "no_grpc_header": false,  // stream-up/one
	"x_padding_bytes": "100-10000",
	"sc_max_each_post_bytes": 1000000, // packet-up only
	"sc_min_posts_interval_ms": 30, // packet-up only
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
    "x_padding_obfs_mode": false,
    "x_padding_key": "",
    "x_padding_header": "",
    "x_padding_placement": "",
    "x_padding_method": "",
    "uplink_http_method": "",
    "session_placement": "",
    "session_key": "",
    "seq_placement": "",
    "seq_key": "",
    "uplink_data_placement": "",
    "uplink_data_key": "",
    "uplink_chunk_size": 0,
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
        "no_grpc_header": false,  // stream-up/one
	    "x_padding_bytes": "100-10000",
	    "sc_max_each_post_bytes": 1000000, // packet-up only
	    "sc_min_posts_interval_ms": 30, // packet-up only
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
        "x_padding_obfs_mode": false,
        "x_padding_key": "",
        "x_padding_header": "",
        "x_padding_placement": "",
        "x_padding_method": "",
        "uplink_http_method": "",
        "session_placement": "",
        "session_key": "",
        "seq_placement": "",
        "seq_key": "",
        "uplink_data_placement": "",
        "uplink_data_key": "",
        "uplink_chunk_size": 0,
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": {
				"enabled": true,
				"public_key": "$(your_publicKey)",
				"short_id": "$(your_shortId)"
			},
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

## Credits

Core: ThroneCore from [Throne](https://github.com/throneproj/Throne), built at the `THRONE_CORE_REF` commit pinned in `nb4a.properties`:

- [throneproj/sing-box](https://github.com/throneproj/sing-box), a fork of [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [throneproj/Xray-core](https://github.com/throneproj/Xray-core), a fork of [XTLS/Xray-core](https://github.com/XTLS/Xray-core)
- [Mahdi-zarei/speedtest-go](https://github.com/Mahdi-zarei/speedtest-go)

Android GUI:

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)

## FAQ
**How does this project differ from NekoBox for Android?** <br/>
It started as a NekoBox for Android fork. v2.0.0 replaced its core and data model with Throne desktop's: the same sing-box and Xray core, profile types, share links, route profiles and backup format. The legacy plugin system was removed, since the built-in core covers what the plugins provided.

**Where are the downloadable route profiles/rulesets coming from?**<br/>
They are located at the [routeprofiles](https://github.com/throneproj/routeprofiles) repository, the same one Throne desktop uses.

**Why are some servers skipped when I import a link or a subscription?**<br/>
As on desktop, Extra Core, Chain and Auto Selector profiles are never imported from links, subscriptions or shared route profiles; create them in the app instead. Nodes the app cannot run are skipped too, and the import reports what was left out.

## Star History

<a href="https://www.star-history.com/?repos=throneproj%2FThroneForAndroid&type=date&legend=bottom-right">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&theme=dark&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=throneproj/ThroneForAndroid&type=date&legend=bottom-right&sealed_token=vVh7Hn3UTnDoalet423u1x-LDNiIZQ2VfWY7GGmbMR8V-4feGR0yTT_IpVxBrxSTOwF7xFnsTcZjyHqNufLeTmdL5f-lw36iYWXcJSlXuJwapM1s8wChkg" />
 </picture>
</a>
