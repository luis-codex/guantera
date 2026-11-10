package app.guantera.ui.history

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import app.guantera.databinding.ViewHistoryNavBinding

/** Anterior, a title and Siguiente. Siguiente is invisible, not dead, when there is nothing newer. */
class NavRowView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewHistoryNavBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    fun setActions(previous: () -> Unit, next: () -> Unit) {
        binding.previous.setOnClickListener { previous() }
        binding.next.setOnClickListener { next() }
    }

    fun render(title: String, caption: String?, canGoNext: Boolean) {
        binding.title.text = title
        binding.caption.isVisible = caption != null
        binding.caption.text = caption
        binding.next.visibility = if (canGoNext) View.VISIBLE else View.INVISIBLE
    }
}
