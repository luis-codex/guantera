package app.guantera.ui.components

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import app.guantera.R
import app.guantera.databinding.ViewConfirmOverlayBinding
import app.guantera.databinding.ViewFieldCellBinding
import app.guantera.databinding.ViewNumberPadBinding
import app.guantera.databinding.ViewPagerBarBinding
import app.guantera.databinding.ViewTextFieldBinding
import app.guantera.presentation.input.PadExtra
import app.guantera.presentation.input.PadKey

/**
 * On-screen number pad with large keys, so amounts, kilometers and dates never open the Android
 * keyboard. 3 x 4 keys of 96 dp: the digits, a delete key, zero and one spare key that is a comma
 * for money, "Hoy" for dates and absent for whole numbers. Reusable in any form.
 */
class NumberPadView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewNumberPadBinding.inflate(LayoutInflater.from(context), this)
    private var onKey: (PadKey) -> Unit = {}

    init {
        orientation = VERTICAL
        listOf(
            binding.key0, binding.key1, binding.key2, binding.key3, binding.key4,
            binding.key5, binding.key6, binding.key7, binding.key8, binding.key9,
        ).forEachIndexed { digit, button -> button.setOnClickListener { onKey(PadKey.Digit(digit)) } }
        binding.keyBackspace.setOnClickListener { onKey(PadKey.Backspace) }
        setExtra(PadExtra.None)
    }

    fun setOnKey(listener: (PadKey) -> Unit) {
        onKey = listener
    }

    /** The spare key; without one it is invisible rather than a dead button. */
    fun setExtra(extra: PadExtra) {
        val key = binding.keyExtra
        when (extra) {
            PadExtra.None -> {
                key.visibility = INVISIBLE
                key.setOnClickListener(null)
            }
            PadExtra.Comma -> {
                key.visibility = VISIBLE
                key.setText(R.string.lg_pad_comma)
                key.setTextAppearanceForPad(true)
                key.setOnClickListener { onKey(PadKey.Comma) }
            }
            PadExtra.Today -> {
                key.visibility = VISIBLE
                key.setText(R.string.lg_pad_today)
                key.setTextAppearanceForPad(false)
                key.setOnClickListener { onKey(PadKey.Today) }
            }
        }
    }

    private fun android.widget.Button.setTextAppearanceForPad(figure: Boolean) {
        val font = ResourcesCompat.getFont(context, if (figure) R.font.fraunces_regular else R.font.space_grotesk_medium)
        typeface = font
        setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(if (figure) R.dimen.text_title else R.dimen.text_secondary),
        )
    }
}

/** A tappable form cell: small label over the value. The active one wears the signal border. */
class FieldCellView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewFieldCellBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
        setBackgroundResource(R.drawable.bg_field)
        val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
        setPadding(padding, padding, padding, padding)
        isClickable = true
        isFocusable = true
        context.withStyledAttributes(attrs, R.styleable.FieldCellView) {
            binding.label.text = getString(R.styleable.FieldCellView_fieldLabel)
        }
    }

    /** An empty value shows a dash in the faint ink, so a missing number is never mistaken for zero. */
    fun setValue(text: CharSequence, empty: Boolean) {
        binding.value.text = if (empty) context.getString(R.string.lg_empty_value) else text
        binding.value.setTextColor(context.getColor(if (empty) R.color.ink_faint else R.color.ink))
    }

    fun setOnSelect(action: () -> Unit) = setOnClickListener { action() }
}

/** Same cell for free text. This is the only input that opens the system keyboard. */
class TextFieldView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewTextFieldBinding.inflate(LayoutInflater.from(context), this)
    private var onChange: (String) -> Unit = {}
    private var onFocus: () -> Unit = {}
    private var onRelease: () -> Unit = {}

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
        setBackgroundResource(R.drawable.bg_field)
        val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
        setPadding(padding, padding, padding, padding)
        context.withStyledAttributes(attrs, R.styleable.FieldCellView) {
            binding.label.text = getString(R.styleable.FieldCellView_fieldLabel)
        }
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
        binding.input.setOnFocusChangeListener { _, focused ->
            isActivated = focused
            if (focused) onFocus() else onRelease()
        }
        binding.input.setOnEditorActionListener { view, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) view.clearFocus()
            false
        }
        setOnClickListener { binding.input.requestFocus(); showKeyboard() }
    }

    fun setOnTextChanged(listener: (String) -> Unit) {
        onChange = listener
    }

    /** Called when the field loses focus, which is when the system keyboard closes. */
    fun setOnReleased(listener: () -> Unit) {
        onRelease = listener
    }

    fun setOnFocused(listener: () -> Unit) {
        onFocus = listener
    }

    /** Only writes when the text differs, so rendering a state never moves the cursor. */
    fun setText(text: String) {
        if (binding.input.text.toString() != text) {
            binding.input.setText(text)
            binding.input.setSelection(text.length)
        }
    }

    fun setError(error: Boolean) {
        isSelected = error
    }

    fun releaseFocus() {
        if (binding.input.hasFocus()) {
            binding.input.clearFocus()
            hideKeyboard()
        }
    }

    private fun showKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.input.windowToken, 0)
    }
}

/** Option button for short lists: the chosen one has a border, a fill and a heavier letter, never only a color. */
class ChoiceButton(context: Context, attrs: AttributeSet?) : AppCompatButton(context, attrs) {
    private val regular = ResourcesCompat.getFont(context, R.font.space_grotesk_regular)
    private val medium = ResourcesCompat.getFont(context, R.font.space_grotesk_medium)

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        typeface = if (selected) medium else regular
    }
}

/** Anterior, "2 de 5", Siguiente. The buttons that lead nowhere are invisible instead of dead. */
class PagerBarView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewPagerBarBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun setActions(previous: () -> Unit, next: () -> Unit) {
        binding.previous.setOnClickListener { previous() }
        binding.next.setOnClickListener { next() }
    }

    /** Hidden entirely when everything fits on one page. */
    fun render(index: Int, count: Int) {
        visibility = if (count > 1) VISIBLE else INVISIBLE
        binding.previous.visibility = if (index > 0) VISIBLE else INVISIBLE
        binding.next.visibility = if (index < count - 1) VISIBLE else INVISIBLE
        binding.label.text = context.getString(R.string.lg_page, index + 1, count)
    }
}

/** Question with two answers over a dimmed screen. Swallows touches behind it. */
class ConfirmOverlayView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private val binding = ViewConfirmOverlayBinding.inflate(LayoutInflater.from(context), this)

    init {
        setBackgroundColor(context.getColor(R.color.scrim))
        isClickable = true
        isFocusable = true
        isVisible = false
    }

    fun show(
        title: CharSequence,
        body: CharSequence,
        confirmText: CharSequence,
        cancelText: CharSequence,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        isVisible = true
        binding.title.text = title
        binding.body.text = body
        binding.confirm.text = confirmText
        binding.cancel.text = cancelText
        binding.confirm.setOnClickListener { onConfirm() }
        binding.cancel.setOnClickListener { onCancel() }
    }

    fun hide() {
        isVisible = false
    }
}

/** Closes the system keyboard, used when the driver goes back to the pad. */
fun View.hideSystemKeyboard() {
    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
}
