package com.example.unicitywallet.ui.settings

import android.content.ClipData
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.unicitywallet.databinding.FragmentRecoveryPhraseBinding
import android.content.ClipboardManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.unicitywallet.R
import com.example.unicitywallet.identity.IdentityManager
import kotlinx.coroutines.launch
import android.widget.TextView

class RecoveryPhraseActivity : AppCompatActivity() {
    private lateinit var binding: FragmentRecoveryPhraseBinding
    private val mnemonicAdapter = MnemonicAdapter()
    private var recoveryWords: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = FragmentRecoveryPhraseBinding.inflate(layoutInflater)

        setContentView(binding.root)

        // навигация назад
        binding.btnBack.setOnClickListener { finish() }

        // список слов (2 колонки)
        binding.rvWords.apply {
            layoutManager = GridLayoutManager(this@RecoveryPhraseActivity, 2)
            adapter = mnemonicAdapter
            setHasFixedSize(true)
            if (itemDecorationCount == 0) addItemDecoration(GridSpacingDecoration(span = 2, spacingPx = dp(10)))
        }

        lifecycleScope.launch {
            recoveryWords = loadMnemonic()
            mnemonicAdapter.submit(recoveryWords)
        }

        // старт: показываем оверлей и блюрим/затемняем фон
        binding.overlay.isVisible = true
        setBlur(binding.contentContainer, 90f)
        binding.btnCopy.isEnabled = false

        // логика подтверждения
        binding.cbUnderstand.setOnCheckedChangeListener { _, checked ->
            binding.btnReveal.isEnabled = checked
        }

        binding.btnReveal.setOnClickListener {
            // плавно скрываем оверлей
            binding.overlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    binding.overlay.isVisible = false
                    binding.overlay.alpha = 1f
                }.start()

            // снимаем блюр
            setBlur(binding.contentContainer, 0f)
            binding.btnCopy.isEnabled = true
        }

        // копирование фразы
        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("recovery phrase", recoveryWords.joinToString(" ")))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }


    private suspend fun loadMnemonic(): List<String> {
        val identityManager = IdentityManager(this)
        val phrase = identityManager.getSeedPhrase()
        Log.d("Recovery", "$phrase")
        return phrase ?: emptyList()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Нативный blur на Android 12+, на старых — лёгкое затемнение */
    private fun setBlur(target: View, radius: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            target.setRenderEffect(
                if (radius > 0f)
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                else null
            )
        } else {
            target.alpha = if (radius > 0f) 0.5f else 1f
        }
    }
}

/** Отступы в Grid, чтобы «чипы» слов выглядели ровно */
class GridSpacingDecoration(private val span: Int, private val spacingPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        val col = pos % span
        outRect.left = spacingPx - col * spacingPx / span
        outRect.right = (col + 1) * spacingPx / span
        if (pos >= span) outRect.top = spacingPx
    }
}

/** Простой адаптер для 12 слов — замени на свой, если уже есть */
class MnemonicAdapter(private val columns: Int = 2)
    : RecyclerView.Adapter<MnemonicAdapter.Holder>() {

    private var words: List<String> = emptyList()

    fun submit(newWords: List<String>) {
        words = newWords
        notifyDataSetChanged()
    }

    private fun rows(): Int = (words.size + columns - 1) / columns

    override fun getItemCount(): Int = rows() * columns
    // ↑ чтобы сетка была ровной: при нечётном количестве правый нижний будет пустым

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): Holder {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_mnemonic_word, p, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = position / columns        // номер строки
        val c = position % columns        // номер колонки (0=левый, 1=правый)
        val srcIndex = r + c * rows()     // МАГИЯ: индекс из column-major

        val word = words.getOrNull(srcIndex)
        if (word == null) {
            // если слов не хватает (нечётное число) — прячем ячейку, чтобы сетка не ломалась
            h.itemView.visibility = View.INVISIBLE
        } else {
            h.itemView.visibility = View.VISIBLE
            h.bind(srcIndex, word)        // srcIndex + 1 = правильный порядковый номер
        }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        private val tvIndex = v.findViewById<TextView>(R.id.tvIndex)
        private val tvWord  = v.findViewById<TextView>(R.id.tvWord)
        fun bind(srcIndex: Int, w: String) {
            tvIndex.text = "${srcIndex + 1}."
            tvWord.text  = w
        }
    }
}