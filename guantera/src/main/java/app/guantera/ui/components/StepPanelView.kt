package app.guantera.ui.components

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ViewStepPanelBinding

/** One button of a step. [primary] is the single filled one: only one per step. */
data class StepButton(val text: CharSequence, val primary: Boolean, val onClick: () -> Unit)

/**
 * One question or one message per screen, with the buttons stacked under it. P0 and P12 are built
 * from it, so neither depends on the shell, the rail or any list of the rest of the app.
 */
class StepPanelView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private val binding = ViewStepPanelBinding.inflate(LayoutInflater.from(context), this, true)

    init { isFillViewport = true }

    val inputText: String get() = binding.input.text.toString()
    val inputView get() = binding.input

    fun show(kicker: CharSequence?, title: CharSequence, message: CharSequence?, footer: CharSequence? = null) {
        binding.kicker.isVisible = kicker != null
        binding.kicker.text = kicker
        binding.title.text = title
        binding.message.isVisible = message != null
        binding.message.text = message
        binding.footer.isVisible = footer != null
        binding.footer.text = footer
    }

    /** Shows the text field with [initial] only when it was not visible already, so typing is never overwritten. */
    fun showInput(inputType: Int, initial: String) {
        if (!binding.input.isVisible) {
            binding.input.setText(initial)
            binding.input.setSelection(binding.input.text.length)
        }
        binding.input.inputType = inputType
        binding.input.isVisible = true
    }

    fun hideInput() { binding.input.isVisible = false }

    fun setError(text: CharSequence?) {
        binding.error.isVisible = text != null
        binding.error.text = text
    }

    fun setButtons(buttons: List<StepButton>) {
        binding.buttons.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.touch_gap)
        buttons.forEachIndexed { index, spec ->
            val style = if (spec.primary) R.style.Control_Button_Primary else R.style.Control_Button_Secondary
            val button = Button(context, null, 0, style).apply {
                text = spec.text
                minWidth = resources.getDimensionPixelSize(R.dimen.action_min_width)
                setOnClickListener { spec.onClick() }
            }
            binding.buttons.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, resources.getDimensionPixelSize(R.dimen.touch_min)).apply {
                    if (index > 0) topMargin = gap
                },
            )
        }
    }
}
