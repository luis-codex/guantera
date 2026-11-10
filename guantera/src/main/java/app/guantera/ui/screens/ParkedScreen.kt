package app.guantera.ui.screens

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import app.guantera.R
import app.guantera.databinding.ScreenParkedBinding
import app.guantera.presentation.parked.ParkedContent
import app.guantera.presentation.parked.ParkedError
import app.guantera.presentation.parked.ParkedPresenter
import app.guantera.presentation.parked.ParkedViewState
import app.guantera.ui.Screen

/** P10: says where the car was left, and hands the location to the phone through a QR code. */
class ParkedScreen(
    parent: ViewGroup,
    private val presenter: ParkedPresenter,
    private val onKeyboardClosed: () -> Unit,
) : Screen() {
    private val binding = ScreenParkedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    private val context = parent.context
    override val view = binding.root

    init {
        binding.saveHere.setOnClickListener { presenter.saveHere() }
        binding.editNote.setOnClickListener { presenter.editNote() }
        binding.saveNote.setOnClickListener { saveNote() }
        binding.cancelNote.setOnClickListener { presenter.cancelNote() }
        binding.stoppedYes.setOnClickListener { presenter.confirmStopped() }
        binding.stoppedNo.setOnClickListener { presenter.denyStopped() }
        binding.noteField.doAfterTextChanged { presenter.onDraftChanged(it?.toString().orEmpty()) }
        binding.noteField.setOnEditorActionListener { _, action, event ->
            // Some head-unit keyboards, and physical ones, send a plain Enter instead of the Done action.
            val plainEnter = action == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (action == EditorInfo.IME_ACTION_DONE || (plainEnter && event?.action == KeyEvent.ACTION_DOWN)) saveNote()
            action == EditorInfo.IME_ACTION_DONE || plainEnter
        }
    }

    override suspend fun observe() = presenter.state.collect(::render)

    override fun onBack(): Boolean = presenter.cancelNote()

    override fun onLeave() {
        presenter.cancelNote()
    }

    private fun saveNote() {
        presenter.saveNote()
    }

    private fun render(state: ParkedViewState) {
        val saved = state.content as? ParkedContent.Saved
        binding.savedGroup.isVisible = saved != null && !state.isEditingNote
        binding.emptyGroup.isVisible = saved == null && !state.isEditingNote
        binding.qrGroup.isVisible = saved != null
        saved?.let(::renderSaved)
        renderError(state.error)
        renderActions(state, hasSpot = saved != null)
        renderEditor(state)
    }

    private fun renderSaved(saved: ParkedContent.Saved) {
        binding.time.text = saved.timeText
        binding.age.text = context.ageText(saved.ageMinutes)
        binding.rowNote.setValue(saved.note ?: context.getString(R.string.parked_no_note))
        binding.rowCoordinates.setValue(saved.coordinatesText)
        binding.rowAccuracy.setValue(context.getString(R.string.parked_accuracy_value, saved.accuracyMeters))
        binding.lastSignal.isVisible = saved.isLastSignalPoint
        binding.editNote.setText(if (saved.note == null) R.string.parked_add_note else R.string.parked_edit_note)
        binding.qr.setContent(saved.mapUrl)
    }

    private fun renderError(error: ParkedError?) {
        binding.error.isVisible = error != null
        when (error) {
            ParkedError.NoFix -> binding.error.setText(R.string.parked_no_fix)
            ParkedError.Moving -> binding.error.setText(R.string.parked_moving)
            null -> Unit
        }
    }

    private fun renderActions(state: ParkedViewState, hasSpot: Boolean) {
        binding.stoppedGroup.isVisible = state.askIfStopped
        binding.actions.isVisible = !state.askIfStopped
        binding.saveHere.isVisible = !state.isEditingNote
        binding.editNote.isVisible = !state.isEditingNote && hasSpot
        binding.saveNote.isVisible = state.isEditingNote
        binding.cancelNote.isVisible = state.isEditingNote
    }

    /** The text is only pushed when it differs, so typing never moves the cursor. */
    private fun renderEditor(state: ParkedViewState) {
        val wasEditing = binding.editGroup.isVisible
        binding.editGroup.isVisible = state.isEditingNote
        if (state.isEditingNote) {
            if (binding.noteField.text.toString() != state.draft) {
                binding.noteField.setText(state.draft)
                binding.noteField.setSelection(state.draft.length)
            }
            if (!wasEditing) showKeyboard()
        } else if (wasEditing) {
            hideKeyboard()
        }
    }

    private fun showKeyboard() {
        binding.noteField.requestFocus()
        context.getSystemService<InputMethodManager>()?.showSoftInput(binding.noteField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(binding.noteField.windowToken, 0)
        onKeyboardClosed()
    }
}
