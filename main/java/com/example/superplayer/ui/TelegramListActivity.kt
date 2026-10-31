package com.example.superplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.ChannelOverrides
import com.example.superplayer.data.FavoritesStore
import com.example.superplayer.databinding.ActivityTgListBinding
import com.example.superplayer.model.Category
import com.example.superplayer.model.PlaylistData
import com.example.superplayer.model.Stream
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper

/**
 * Visor de una lista que llega por Telegram (.m3u, .w3u, .json…): a la izquierda los grupos (con la imagen de su
 * primer canal), a la derecha los canales del grupo elegido, y un buscador que recorre toda la lista. Tocar un canal lo
 * reproduce; mantener pulsado da el menú de siempre (favorito, renombrar, ocultar…). La lista llega por [pending].
 */
class TelegramListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTgListBinding
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var streamAdapter: StreamAdapter
    private lateinit var groupAdapter: GroupAdapter
    private var data: PlaylistData = PlaylistData(emptyList())
    private var selected = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTgListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        applySystemBarInsets(top = binding.toolbar, bottom = binding.recyclerView)
        favoritesStore = FavoritesStore(this)
        data = pending ?: PlaylistData(emptyList())
        title = pendingTitle.ifBlank { getString(R.string.tg_playlist_title, data.categories.sumOf { it.streams.size }) }

        streamAdapter = StreamAdapter(
            items = emptyList(),
            isFavorite = { favoritesStore.isFavorite(it) },
            onClick = { openPlayer(it) },
            onToggleFavorite = { toggleFavorite(it) },
            onLongClick = { stream ->
                showChannelOptionsDialog(
                    this, stream, favoritesStore.isFavorite(stream),
                    onToggleFavorite = { toggleFavorite(stream) },
                    onChanged = { refresh() }
                )
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = streamAdapter
        groupAdapter = GroupAdapter()
        binding.groupList.layoutManager = LinearLayoutManager(this)
        binding.groupList.adapter = groupAdapter
        // Con un solo grupo no hace falta la columna de la izquierda.
        binding.groupList.visibility = if (data.categories.size > 1) View.VISIBLE else View.GONE
        binding.listSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                query = e?.toString().orEmpty().trim()
                refresh()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        streamAdapter.notifyDataSetChanged()
    }

    /** Los canales que se ven: los de todo el archivo si hay búsqueda, si no los del grupo elegido (sin los ocultos, con los nombres propios). */
    private fun visibleStreams(): List<Stream> {
        val base = if (query.isNotEmpty()) data.categories.flatMap { it.streams }
        else data.categories.getOrNull(selected)?.streams.orEmpty()
        val list = base.filter { !ChannelOverrides.isHidden(this, it.id) }.map { ChannelOverrides.renamed(this, it) }
        return if (query.isEmpty()) list else list.filter { it.name.contains(query, ignoreCase = true) }
    }

    private fun refresh() {
        val list = visibleStreams()
        streamAdapter.submit(list)
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        groupAdapter.notifyDataSetChanged()
    }

    private fun openPlayer(stream: Stream) {
        PlayerActivity.pendingStream = stream
        PlayerActivity.pendingChannelList = visibleStreams()
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toggleFavorite(stream: Stream) {
        val nowFav = favoritesStore.toggle(stream)
        Toast.makeText(this, getString(if (nowFav) R.string.added_to_favorites else R.string.removed_from_favorites), Toast.LENGTH_SHORT).show()
        streamAdapter.notifyDataSetChanged()
        ShortcutsHelper.refresh(this, favoritesStore.getAllStreams())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_tg_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_tgview_main -> {
                TelegramImport.openInMain(this, data)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Columna de grupos: la imagen del primer canal con icono y el nombre (2 líneas). */
    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.VH>() {
        inner class VH(val root: LinearLayout, val icon: ImageView, val name: TextView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = data.categories.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val icon = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF222233.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(80))
            }
            val name = TextView(parent.context).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(parent.context, R.color.on_background))
                setPadding(dp(4), dp(6), dp(4), 0)
            }
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                addView(icon)
                addView(name)
            }
            return VH(root, icon, name)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cat: Category = data.categories[position]
            holder.name.text = cat.name
            val img = cat.streams.firstOrNull { !it.icon.isNullOrBlank() }?.icon
            if (img != null) holder.icon.load(img) else holder.icon.setImageDrawable(null)
            holder.root.setBackgroundResource(R.drawable.bg_list_item)
            holder.root.isSelected = position == selected && query.isEmpty()
            holder.root.alpha = if (position == selected && query.isEmpty()) 1f else 0.75f
            holder.root.setOnClickListener {
                selected = holder.adapterPosition.coerceAtLeast(0)
                if (query.isNotEmpty()) binding.listSearch.setText("") else refresh()
            }
        }
    }

    companion object {
        /** Lista que se va a enseñar y su título: la deja aquí TelegramAccountActivity antes de abrir esta pantalla. */
        @Volatile
        var pending: PlaylistData? = null
        @Volatile
        var pendingTitle: String = ""
    }
}
