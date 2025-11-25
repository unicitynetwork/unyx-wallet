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

        binding.btnBack.setOnClickListener { finish() }

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

        binding.overlay.isVisible = true
        setBlur(binding.contentContainer, 90f)
        binding.btnCopy.isEnabled = false

        binding.cbUnderstand.setOnCheckedChangeListener { _, checked ->
            binding.btnReveal.isEnabled = checked
        }

        binding.btnReveal.setOnClickListener {
            binding.overlay.animate()
                .alpha(0f)
                .setDuration(200L)
                .withEndAction {
                    binding.overlay.isVisible = false
                    binding.overlay.alpha = 1f
                }.start()

            setBlur(binding.contentContainer, 0f)
            binding.btnCopy.isEnabled = true
        }

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

class MnemonicAdapter(private val columns: Int = 2)
    : RecyclerView.Adapter<MnemonicAdapter.Holder>() {

    private var words: List<String> = emptyList()

    fun submit(newWords: List<String>) {
        words = newWords
        notifyDataSetChanged()
    }

    private fun rows(): Int = (words.size + columns - 1) / columns

    override fun getItemCount(): Int = rows() * columns

    override fun onCreateViewHolder(p: ViewGroup, vt: Int): Holder {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_mnemonic_word, p, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = position / columns
        val c = position % columns
        val srcIndex = r + c * rows()

        val word = words.getOrNull(srcIndex)
        if (word == null) {
            h.itemView.visibility = View.INVISIBLE
        } else {
            h.itemView.visibility = View.VISIBLE
            h.bind(srcIndex, word)
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