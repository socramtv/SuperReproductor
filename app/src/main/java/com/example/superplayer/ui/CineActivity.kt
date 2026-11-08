package com.example.superplayer.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.AppPrefs
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.data.MovieInfo
import com.example.superplayer.databinding.ActivityCineBinding
import com.example.superplayer.model.Category
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import java.text.Normalizer
import java.util.Locale

/**
 * Cine: parrilla de carátulas de las películas de tus listas, con buscador,
 * y al tocar una, su ficha (carátula grande, año, género y sinopsis) con
 * "Ver". Las categorías que cuentan como cine se detectan por el nombre
 * (película, cine, movie, film, vod, estreno...) y se pueden elegir a mano
 * con el botón "Categorías".
 */
class CineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCineBinding
    private lateinit var adapter: CineAdapter
    private lateinit var rowsAdapter: CineRowsAdapter
    private var rowPosterWidth = 0
    private var rowPosterHeight = 0
    private var rows: List<Row> = emptyList()
    private var query = ""
    private var posterHeight = 0
    private var selectedCategory = 0 // 0 = todas

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.example.superplayer.data.AccessControl.guardActivity(this, com.example.superplayer.data.AccessControl.S_CINE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.cine_title)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = if (isTv) 6 else (widthDp / 120f).toInt().coerceIn(3, 8)
        val cellWidthPx = resources.displayMetrics.widthPixels / columns - (20 * resources.displayMetrics.density).toInt()
        posterHeight = (cellWidthPx * 1.5f).toInt()

        adapter = CineAdapter(emptyList(), { posterHeight }) { stream -> showDetails(stream) }
        binding.recyclerView.layoutManager = GridLayoutManager(this, columns)
        binding.recyclerView.adapter = adapter

        // Filas al estilo Netflix: carátulas en horizontal, un poco más pequeñas que en la parrilla.
        rowPosterWidth = ((if (isTv) 150f else 118f) * resources.displayMetrics.density).toInt()
        rowPosterHeight = (rowPosterWidth * 1.5f).toInt()
        rowsAdapter = CineRowsAdapter(emptyList(), { rowPosterHeight }, { rowPosterWidth }) { stream -> showDetails(stream) }
        binding.rowsView.layoutManager = LinearLayoutManager(this)
        binding.rowsView.adapter = rowsAdapter
        binding.rowsView.setHasFixedSize(false)

        binding.cineSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        setupCategorySpinner()

        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun looksLikeMovies(name: String): Boolean = MOVIE_NAME.containsMatchIn(name)

    /** Una película con su clave de orden y su nombre normalizado ya calculados (así buscar y ordenar miles no bloquea la pantalla). */
    private class Entry(val stream: Stream, val sortKey: String, val norm: String)

    private var entries: List<Entry> = emptyList()
    private var reloadToken = 0

    private fun reload() {
        val token = ++reloadToken
        val list = if (selectedCategory == 0) categories else listOfNotNull(categories.getOrNull(selectedCategory - 1))
        val allCategories = categories
        Thread {
            val built = try {
                list.flatMap { it.streams }
                    .distinctBy { it.id }
                    .map { Entry(it, MovieInfo.cleanTitle(it.name).first.lowercase(Locale.ROOT), normalize(it.name)) }
                    .sortedBy { it.sortKey }
            } catch (e: Throwable) {
                emptyList()
            }
            val builtRows = try { buildRows(allCategories) } catch (e: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || token != reloadToken) return@runOnUiThread
                entries = built
                rows = builtRows
                applyFilter()
            }
        }.start()
    }

    private val markRegex = Regex("\\p{M}+")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(markRegex, "")

    /** Una fila de Cine: título y sus películas. */
    class Row(val title: String, val streams: List<Stream>)

    /**
     * Filas automáticas: primero una por cada categoría de la lista (las que
     * existan, en su orden) y luego una por género (Acción, Animación,
     * Comedia...) con las películas cuya ficha ya se conoce, de modo que
     * salen solas según se van descubriendo.
     */
    private fun buildRows(source: List<Category>): List<Row> {
        val out = ArrayList<Row>()
        val all = LinkedHashMap<String, Stream>()
        for (category in source) {
            val streams = category.streams.distinctBy { it.id }
            if (streams.isEmpty()) continue
            out.add(Row(category.name, streams.take(ROW_MAX)))
            for (s in streams) all.putIfAbsent(s.id, s)
        }
        val byGenre = HashMap<String, MutableList<Stream>>()
        for (stream in all.values) {
            val genre = MovieInfo.peek(this, stream.name)?.genre ?: continue
            for (g in genre.split(",", "/", "&")) {
                val name = g.trim()
                if (name.length < 3) continue
                byGenre.getOrPut(name) { ArrayList() }.add(stream)
            }
        }
        byGenre.entries
            .filter { it.value.size >= GENRE_MIN }
            .sortedByDescending { it.value.size }
            .take(GENRE_ROWS)
            .forEach { out.add(Row("🎭 " + it.key, it.value.take(ROW_MAX))) }
        return out
    }

    private fun applyFilter() {
        val q = normalize(query.trim())
        if (q.isEmpty() && selectedCategory == 0) {
            binding.rowsView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            rowsAdapter.submit(rows)
            binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        binding.rowsView.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        val shown = (if (q.isEmpty()) entries else entries.filter { it.norm.contains(q) }).map { it.stream }
        adapter.submit(shown)
        binding.emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupCategorySpinner() {
        val names = ArrayList<String>()
        names.add(getString(R.string.cine_all_categories))
        categories.forEach { names.add("${it.name} (${it.streams.size})") }
        val textColor = ContextCompat.getColor(this, R.color.on_background)
        val adapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(textColor)
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(textColor)
                v.setPadding(32, 28, 32, 28)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cineCategorySpinner.adapter = adapter
        binding.cineCategorySpinner.setPopupBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this, R.color.surface_dark))
        )
        binding.cineCategorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != selectedCategory) {
                    selectedCategory = position
                    reload()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun showDetails(stream: Stream) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val cBg = 0xFF12121C.toInt()
        val cCard = 0xFF222232.toInt()
        val cText = 0xFFFFFFFF.toInt()
        val cMuted = 0xFFB4B4C6.toInt()
        val accent = ContextCompat.getColor(this, R.color.primary)
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val favorites = FavoritesStore(this)

        fun shape(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }
        fun focusBg(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, radius, 3, cText))
            addState(intArrayOf(android.R.attr.state_pressed), shape(fill, radius, 3, cText))
            addState(intArrayOf(), shape(fill, radius, stroke, strokeColor))
        }
        fun pill(text: String, fill: Int, sizeSp: Float = 14f, heightDp: Int = 44, stroke: Int = 0, strokeColor: Int = 0, onClick: () -> Unit) =
            TextView(this).apply {
                this.text = text
                textSize = sizeSp
                setTextColor(cText)
                gravity = android.view.Gravity.CENTER
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                background = focusBg(fill, heightDp / 2, stroke, strokeColor)
                setPadding(dp(14), 0, dp(14), 0)
                minHeight = dp(heightDp)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
        fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
            LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        lateinit var playBtn: View

        fun render(info: MovieInfo.Info?, searching: Boolean) {
            col.removeAllViews()
            // Cabecera con fondo y degradado
            val header = android.widget.FrameLayout(this)
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(cCard)
            }
            val headerUrl = info?.backdropUrl?.takeIf { it.isNotBlank() } ?: info?.posterUrl ?: stream.icon
            if (!headerUrl.isNullOrBlank()) img.load(headerUrl) { error(R.drawable.ic_placeholder) }
            header.addView(img, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            header.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, cBg)
                )
            }, android.widget.FrameLayout.LayoutParams(MATCH, dp(130), android.view.Gravity.BOTTOM))
            col.addView(header, lp(MATCH, dp(240)))

            col.addView(TextView(this).apply {
                text = info?.title?.takeIf { it.isNotBlank() } ?: MovieInfo.cleanTitle(stream.name).first
                textSize = 24f
                setTextColor(cText)
                gravity = android.view.Gravity.CENTER
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(20), 0, dp(20), 0)
            }, lp(MATCH, WRAP, t = -34))

            if (info != null) {
                val facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
                if (info.rating > 0.0) facts.addView(TextView(this).apply {
                    text = "★ " + String.format(Locale.US, "%.1f", info.rating)
                    textSize = 14f
                    setTextColor(0xFFFFC107.toInt())
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                    background = shape(0x33FFC107, 14)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                }, lp(WRAP, WRAP, r = 8))
                val rest = listOfNotNull(
                    info.year,
                    info.runtimeMin.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" },
                    info.certification?.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                if (rest.isNotEmpty()) facts.addView(TextView(this).apply { text = rest; textSize = 14f; setTextColor(cMuted) })
                col.addView(facts, lp(MATCH, WRAP, t = 8))

                val genres = info.genre?.split(",", "/")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (genres.isNotEmpty()) {
                    val avail = resources.displayMetrics.widthPixels * 0.95f - dp(36)
                    val paint = android.graphics.Paint().apply { textSize = 13f * resources.displayMetrics.scaledDensity }
                    val gbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
                    var line: LinearLayout? = null
                    var used = 0f
                    for (g in genres) {
                        val w = paint.measureText(g) + dp(28) + dp(8)
                        if (line == null || used + w > avail) {
                            line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
                            gbox.addView(line, lp(WRAP, WRAP, t = 6))
                            used = 0f
                        }
                        line!!.addView(TextView(this).apply {
                            text = g
                            textSize = 13f
                            setTextColor(cText)
                            maxLines = 1
                            background = shape(0x1AFFFFFF, 16, 1, 0x40FFFFFF)
                            setPadding(dp(14), dp(6), dp(14), dp(6))
                        }, lp(WRAP, WRAP, l = 4, r = 4))
                        used += w
                    }
                    col.addView(gbox, lp(MATCH, WRAP, t = 6))
                }
            }

            // Botón grande
            playBtn = pill(getString(R.string.info_play), accent, sizeSp = 16f, heightDp = 46) {
                dialog.dismiss()
                PlayerActivity.pendingStream = stream
                PlayerActivity.pendingChannelList = emptyList()
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            col.addView(playBtn, lp(MATCH, dp(46), l = 28, t = 16, r = 28))

            // Fila: tráiler, favoritos
            val ghost = 0x1FFFFFFF
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tk = info?.trailerKey
            if (!tk.isNullOrBlank()) row.addView(pill(getString(R.string.info_trailer), ghost, stroke = 1, strokeColor = 0x40FFFFFF) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$tk")))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, R.string.cine_trailer_error, android.widget.Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(6), 0) })
            lateinit var favBtn: TextView
            favBtn = pill(getString(if (favorites.isFavorite(stream)) R.string.info_fav_on else R.string.info_fav_add), ghost, stroke = 1, strokeColor = 0x40FFFFFF) {
                favorites.toggle(stream)
                favBtn.text = getString(if (favorites.isFavorite(stream)) R.string.info_fav_on else R.string.info_fav_add)
            }
            row.addView(favBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
            col.addView(row, lp(MATCH, dp(44), l = 28, t = 10, r = 28))

            // Sinopsis
            val tag = info?.tagline?.takeIf { it.isNotBlank() }
            val over = info?.overview?.takeIf { it.isNotBlank() }
                ?: getString(if (searching) R.string.cine_searching else R.string.cine_no_synopsis)
            col.addView(TextView(this).apply {
                text = if (tag != null) "“$tag”\n\n$over" else over
                textSize = 15f
                setTextColor(cMuted)
                setLineSpacing(0f, 1.25f)
            }, lp(MATCH, WRAP, l = 24, t = 18, r = 24))

            // Reparto y equipo
            val people = info?.people.orEmpty().mapNotNull { e -> e.split("\t").takeIf { it.size >= 4 } }
            if (people.isNotEmpty()) {
                col.addView(TextView(this).apply {
                    text = getString(R.string.info_cast_title)
                    textSize = 17f
                    setTextColor(cText)
                    typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                }, lp(MATCH, WRAP, l = 24, t = 20))
                val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), 0) }
                for (p in people) {
                    val isDir = p[0] == "D"
                    val ring = if (isDir) 0xFFFFC107.toInt() else 0xFF3A3A4E.toInt()
                    val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
                    val frame = android.widget.FrameLayout(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(cCard); setStroke(dp(2), ring) }
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                    }
                    val face = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
                        }
                        setImageResource(android.R.drawable.ic_menu_myplaces)
                        setColorFilter(0xFF6B6B80.toInt())
                    }
                    if (p[3].isNotEmpty()) { face.clearColorFilter(); face.load(p[3]) }
                    frame.addView(face, android.widget.FrameLayout.LayoutParams(dp(68), dp(68)))
                    cell.addView(frame, lp(WRAP, WRAP))
                    cell.addView(TextView(this).apply {
                        text = p[1]; textSize = 13f; setTextColor(cText); gravity = android.view.Gravity.CENTER; maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, lp(dp(88), WRAP, t = 6))
                    val sub = if (isDir) getString(R.string.info_director_label) else p[2]
                    if (sub.isNotEmpty()) cell.addView(TextView(this).apply {
                        text = sub; textSize = 11f; gravity = android.view.Gravity.CENTER; maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(if (isDir) 0xFFFFC107.toInt() else cMuted)
                    }, lp(dp(88), WRAP, t = 1))
                    strip.addView(cell, lp(WRAP, WRAP, l = 4, r = 4))
                }
                hs.addView(strip)
                col.addView(hs, lp(MATCH, WRAP))
            } else if (info != null) {
                val credits = ArrayList<String>()
                info.director?.takeIf { it.isNotBlank() }?.let {
                    credits.add(getString(if (info.isTv) R.string.cine_creator else R.string.cine_director) + ": " + it)
                }
                if (info.cast.isNotEmpty()) credits.add(getString(R.string.cine_cast) + ": " + info.cast.joinToString(", "))
                if (credits.isNotEmpty()) col.addView(TextView(this).apply {
                    text = credits.joinToString("\n\n")
                    textSize = 13f
                    setTextColor(cMuted)
                    setLineSpacing(0f, 1.2f)
                }, lp(MATCH, WRAP, l = 24, t = 14, r = 24))
            }
            col.addView(View(this), lp(MATCH, dp(28)))
        }

        val cached = MovieInfo.peek(this, stream.name)
        val needs = MovieInfo.needsFetch(this, stream.name)
        render(cached, needs && cached == null)
        if (needs) {
            MovieInfo.request(this, stream.name) { found ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && dialog.isShowing) {
                        render(found, false)
                        playBtn.requestFocus()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        val root = android.widget.FrameLayout(this).apply {
            background = shape(cBg, 22)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            addView(TextView(this@CineActivity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(cText)
                gravity = android.view.Gravity.CENTER
                background = focusBg(0x99000000.toInt(), 20)
                isFocusable = true
                isClickable = true
                setOnClickListener { dialog.dismiss() }
            }, android.widget.FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            setLayout((resources.displayMetrics.widthPixels * 0.95f).toInt(), (resources.displayMetrics.heightPixels * 0.92f).toInt())
            setDimAmount(0.75f)
        }
        dialog.show()
        playBtn.requestFocus()
    }

    companion object {
        /** Las categorías de la lista cargada (las rellena MainActivity antes de abrir esta pantalla). */
        var categories: List<Category> = emptyList()

        private const val ROW_MAX = 60
        private const val GENRE_MIN = 4
        private const val GENRE_ROWS = 12

        private val MOVIE_NAME = Regex(
            "pel[ií]cula|peli|cine|movie|film|vod|estreno",
            RegexOption.IGNORE_CASE
        )
    }
}
