package com.example.superplayer.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.sports.GoalAlerts
import com.example.superplayer.sports.MatchFeed
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * «Partidos de hoy»: los partidos del día (marcadores de MatchFeed) con la hora, el resultado si ya juegan
 * y los canales de TU lista que lo emiten según la guía EPG. Tocar un partido abre el canal (o deja elegir entre varios).
 */
object MatchGuide {

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()

    /** Palabra más característica del equipo para filtrar rápido los títulos antes de la comprobación fina. */
    private fun keyWord(team: String): String =
        norm(team).split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("del", "club", "real") }.maxByOrNull { it.length }
            ?: norm(team).take(4)

    fun show(activity: AppCompatActivity, pool: List<Stream>) {
        Toast.makeText(activity, R.string.matchguide_loading, Toast.LENGTH_SHORT).show()
        Thread {
            val cal = Calendar.getInstance()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
            val startOfDay = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val endOfWindow = startOfDay + 30 * 3_600_000L // hasta las 6 de la madrugada siguiente
            val res = try { MatchFeed.fetchAll(date) } catch (e: Exception) { null }
            val matches = res?.matches.orEmpty()
                .filter { it.state == "in" || (it.startMillis in startOfDay until endOfWindow) }
                .distinctBy { it.id }
                .sortedWith(compareBy({ if (it.state == "in") 0 else 1 }, { it.startMillis }))

            // Canales de la lista que emiten cada partido (según la guía): una sola pasada por canal.
            val channels = HashMap<String, MutableList<Stream>>()
            if (matches.isNotEmpty()) {
                val keys = matches.associate { it.id to (keyWord(it.home) to keyWord(it.away)) }
                for (st in pool) {
                    val tvg = st.tvgId
                    if (tvg.isNullOrBlank() || !EpgRepository.hasData(tvg)) continue
                    val entries = EpgRepository.entriesInRange(tvg, startOfDay - 3 * 3_600_000L, endOfWindow + 3 * 3_600_000L)
                    for (e in entries) {
                        val t = norm(e.title)
                        for (m in matches) {
                            val (kh, ka) = keys[m.id] ?: continue
                            if (!t.contains(kh) || !t.contains(ka)) continue
                            if (m.startMillis > 0 && Math.abs(e.startMillis - m.startMillis) > 3 * 3_600_000L) continue
                            if (!GoalAlerts.titleHasMatch(m.home, m.away, e.title)) continue
                            val l = channels.getOrPut(m.id) { ArrayList() }
                            if (l.none { it.id == st.id }) l.add(st)
                        }
                    }
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (matches.isEmpty()) {
                    Toast.makeText(activity, R.string.matchguide_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                render(activity, matches, channels)
            }
        }.start()
    }

    private fun render(activity: AppCompatActivity, matches: List<MatchFeed.Match>, channels: Map<String, List<Stream>>) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(16)) }
        col.addView(TextView(activity).apply {
            text = "⚽ " + activity.getString(R.string.matchguide_title)
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })
        var lastLeague = ""
        for (m in matches) {
            if (m.league != lastLeague) {
                lastLeague = m.league
                col.addView(TextView(activity).apply {
                    text = m.league.uppercase()
                    textSize = 12f
                    setTextColor(0xFF9AA0B8.toInt())
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(12), 0, dp(4))
                })
            }
            val chans = channels[m.id].orEmpty()
            val live = m.state == "in"
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (live) 0x2236C76F else 0x1AFFFFFF)
                    setStroke(dp(1), if (live) 0x6636C76F else 0x22FFFFFF)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    when {
                        chans.isEmpty() -> Toast.makeText(activity, R.string.matchguide_no_channel, Toast.LENGTH_LONG).show()
                        chans.size == 1 -> { dialog.dismiss(); play(activity, chans, 0) }
                        else -> AlertDialog.Builder(activity)
                            .setTitle(m.home + " - " + m.away)
                            .setItems(chans.map { it.name }.toTypedArray()) { _, w -> dialog.dismiss(); play(activity, chans, w) }
                            .show()
                    }
                }
            }
            // Hora o marcador
            row.addView(TextView(activity).apply {
                text = when (m.state) {
                    "in" -> "${m.homeScore}-${m.awayScore}\n" + m.clock
                    "post" -> "${m.homeScore}-${m.awayScore}\nFIN"
                    else -> time.format(Date(m.startMillis))
                }
                textSize = if (m.state == "pre") 16f else 15f
                setTextColor(if (live) 0xFF36C76F.toInt() else Color.WHITE)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
            // Equipos con escudo
            val teams = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun teamRow(name: String, logo: String?) = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(android.widget.ImageView(activity).apply { if (logo != null) load(logo) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(TextView(activity).apply { text = name; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1; setPadding(dp(8), dp(2), 0, dp(2)) })
            }
            teams.addView(teamRow(m.home, m.homeLogo))
            teams.addView(teamRow(m.away, m.awayLogo))
            row.addView(teams, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = if (chans.isEmpty()) "—" else "📺 ${chans.size}"
                textSize = 14f
                setTextColor(if (chans.isEmpty()) 0xFF70758C.toInt() else 0xFFFFC107.toInt())
                typeface = Typeface.create(typeface, Typeface.BOLD)
            })
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        col.addView(TextView(activity).apply {
            text = activity.getString(R.string.matchguide_hint)
            textSize = 12f
            setTextColor(0xFF70758C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val root = android.widget.FrameLayout(activity).apply {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xFF12121C.toInt()) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(scroll, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(activity).apply {
                text = "✕"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x99000000.toInt()) }
                isFocusable = true; isClickable = true
                setOnClickListener { dialog.dismiss() }
            }, android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            setLayout((activity.resources.displayMetrics.widthPixels * 0.95f).toInt(), (activity.resources.displayMetrics.heightPixels * 0.92f).toInt())
            setDimAmount(0.75f)
        }
        dialog.show()
    }

    private fun play(activity: AppCompatActivity, list: List<Stream>, index: Int) {
        PlayerActivity.pendingStream = list[index]
        PlayerActivity.pendingChannelList = list
        activity.startActivity(android.content.Intent(activity, PlayerActivity::class.java))
    }
}
