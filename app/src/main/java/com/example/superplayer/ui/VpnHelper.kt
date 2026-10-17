package com.example.superplayer.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R

/**
 * Botón de VPN: la app no incluye ninguna VPN propia (una VPN necesita servidores
 * y los "gratis" incrustados suelen vender el tráfico). Lo que hace es abrir una
 * VPN gratuita ya instalada, o llevar a instalarla, o a los ajustes de VPN del sistema.
 * Para añadir otra app de VPN, basta con añadir una línea a [KNOWN] y a <queries> del manifest.
 */
object VpnHelper {
    private class VpnApp(val pkg: String, val name: String)

    /** VPN con plan gratuito (en este orden de preferencia). */
    private val KNOWN = listOf(
        VpnApp("com.cloudflare.onedotonedotonedotone", "Cloudflare WARP"),
        VpnApp("com.windscribe.vpn", "Windscribe"),
        VpnApp("ch.protonvpn.android", "Proton VPN"),
        VpnApp("com.wireguard.android", "WireGuard")
    )
    private val INSTALL_SUGGESTIONS = KNOWN.take(2)

    /** True si ahora mismo hay una VPN conectada. */
    fun isActive(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (e: Exception) {
        false
    }

    private fun installed(ctx: Context): List<VpnApp> =
        KNOWN.filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null }

    fun open(activity: Activity) {
        val apps = installed(activity)
        if (apps.size == 1) {
            launch(activity, apps[0])
            return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (a in apps) {
            labels.add(activity.getString(R.string.vpn_open_app, a.name))
            actions.add { launch(activity, a) }
        }
        if (apps.isEmpty()) {
            for (a in INSTALL_SUGGESTIONS) {
                labels.add(activity.getString(R.string.vpn_install_app, a.name))
                actions.add { openStore(activity, a.pkg) }
            }
        }
        labels.add(activity.getString(R.string.vpn_open_settings))
        actions.add { openSystemSettings(activity) }
        val dp = activity.resources.displayMetrics.density
        val list = android.widget.ListView(activity)
        list.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        val explain = android.widget.TextView(activity).apply {
            setText(R.string.vpn_explain)
            textSize = 13f
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(list, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(explain, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.vpn_dialog_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            actions[which]()
        }
        dialog.show()
    }

    private fun launch(activity: Activity, app: VpnApp) {
        val i = activity.packageManager.getLaunchIntentForPackage(app.pkg)
        if (i == null) {
            openStore(activity, app.pkg)
            return
        }
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.vpn_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Play Store, tienda de Amazon (Fire TV) o web, lo primero que responda. */
    private fun openStore(activity: Activity, pkg: String) {
        val uris = listOf(
            "market://details?id=$pkg",
            "amzn://apps/android?p=$pkg",
            "https://play.google.com/store/apps/details?id=$pkg"
        )
        for (u in uris) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return
            } catch (e: ActivityNotFoundException) {
                // prueba la siguiente
            } catch (e: Exception) {
                // prueba la siguiente
            }
        }
        Toast.makeText(activity, R.string.vpn_failed, Toast.LENGTH_LONG).show()
    }

    private fun openSystemSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(activity, R.string.vpn_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
