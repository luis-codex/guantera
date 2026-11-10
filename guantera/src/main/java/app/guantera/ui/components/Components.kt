package app.guantera.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.view.updateLayoutParams
import app.guantera.R
import app.guantera.databinding.ViewBannerBinding
import app.guantera.databinding.ViewFigureCellBinding
import app.guantera.databinding.ViewLabelValueRowBinding
import app.guantera.databinding.ViewListRowBinding
import app.guantera.databinding.ViewRailBinding
import app.guantera.databinding.ViewSectionHeaderBinding
import app.guantera.databinding.ViewTopBarBinding
import app.guantera.databinding.ViewTransportControlsBinding
import app.guantera.databinding.ViewVolumeControlsBinding
import app.guantera.presentation.common.PlaybackUi
import app.guantera.presentation.port.GpsStatus
import app.guantera.presentation.port.MotionStatus
import app.guantera.presentation.shell.Destination
import app.guantera.presentation.shell.ShellViewState

/** "01 — VIAJES" followed by a hairline that runs to the edge. */
class SectionHeaderView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val binding = ViewSectionHeaderBinding.inflate(LayoutInflater.from(context), this)
        context.withStyledAttributes(attrs, R.styleable.SectionHeaderView) {
            val number = getString(R.styleable.SectionHeaderView_sectionNumber)
            val label = getString(R.styleable.SectionHeaderView_sectionLabel)
            binding.label.text = if (number == null) label else context.getString(R.string.section_format, number, label)
        }
    }
}

/** Label on the left, value on the right, joined by a hairline. */
class LabelValueRowView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewLabelValueRowBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        context.withStyledAttributes(attrs, R.styleable.LabelValueRowView) {
            binding.label.text = getString(R.styleable.LabelValueRowView_rowLabel)
        }
    }

    fun setValue(value: CharSequence) { binding.value.text = value }

    /** The one value that answers the screen's question: serif and larger, like the figures. */
    fun emphasize() {
        binding.value.typeface = ResourcesCompat.getFont(context, R.font.fraunces_regular)
        binding.value.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_primary))
        binding.value.fontFeatureSettings = "lnum"
    }
}

/** Large figure over a small label, inside a hairline cell. */
class FigureCellView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewFigureCellBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_cell)
        val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
        setPadding(padding, padding, padding, padding)
        context.withStyledAttributes(attrs, R.styleable.FigureCellView) {
            binding.label.text = getString(R.styleable.FigureCellView_cellLabel)
            val size = getDimension(R.styleable.FigureCellView_cellFigureSize, 0f)
            if (size > 0) binding.figure.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    fun setFigure(value: CharSequence) { binding.figure.text = value }
}

/** Single banner for the most important problem, saying what happened and how to go on. */
class BannerView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewBannerBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_banner)
        val horizontal = resources.getDimensionPixelSize(R.dimen.banner_padding_h)
        val vertical = resources.getDimensionPixelSize(R.dimen.banner_padding_v)
        setPadding(horizontal, vertical, horizontal, vertical)
    }

    /** Without [actionText] the button disappears, so that a banner never holds a dead control. */
    fun show(message: CharSequence, actionText: CharSequence?, onAction: (() -> Unit)?) {
        isVisible = true
        binding.message.text = message
        binding.action.isVisible = actionText != null
        binding.action.text = actionText
        binding.action.setOnClickListener(onAction?.let { action -> OnClickListener { action() } })
    }

    fun hide() { isVisible = false }
}

/** List row with an optional action button. Text never hides the action behind a gesture. */
class ListRowView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewListRowBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        context.withStyledAttributes(attrs, R.styleable.ListRowView) {
            binding.title.text = getString(R.styleable.ListRowView_listTitle)
            binding.subtitle.text = getString(R.styleable.ListRowView_listSubtitle)
            binding.action.text = getString(R.styleable.ListRowView_listAction)
        }
    }

    fun setOnAction(onAction: () -> Unit) { binding.action.setOnClickListener { onAction() } }

    fun setTexts(title: CharSequence, subtitle: CharSequence) {
        binding.title.text = title
        binding.subtitle.text = subtitle
    }

    /** Null hides the button, so that a row never shows a control that cannot act. */
    fun setAction(text: CharSequence?) {
        binding.action.isVisible = text != null
        binding.action.text = text
    }

    fun setSecondary() {
        binding.action.minWidth = resources.getDimensionPixelSize(R.dimen.action_min_width)
        binding.action.setBackgroundResource(R.drawable.bg_button_secondary)
        binding.action.setTextColor(ContextCompat.getColor(context, R.color.ink))
    }
}

/** Previous, play or pause, next. Icons only, as these controls are universal. */
class TransportControlsView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewTransportControlsBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        context.withStyledAttributes(attrs, R.styleable.TransportControlsView) {
            val fallback = resources.getDimensionPixelSize(R.dimen.touch_min)
            val buttonWidth = getDimensionPixelSize(R.styleable.TransportControlsView_transportWidth, fallback)
            val buttonHeight = getDimensionPixelSize(R.styleable.TransportControlsView_transportHeight, fallback)
            listOf(binding.previous, binding.playPause, binding.next).forEach {
                it.updateLayoutParams { width = buttonWidth; height = buttonHeight }
            }
            val gap = getDimensionPixelSize(
                R.styleable.TransportControlsView_transportGap,
                resources.getDimensionPixelSize(R.dimen.button_gap_parked),
            )
            listOf(binding.playPause, binding.next).forEach {
                it.updateLayoutParams<LayoutParams> { marginStart = gap }
            }
        }
    }

    fun setActions(previous: () -> Unit, playPause: () -> Unit, next: () -> Unit) {
        binding.previous.setOnClickListener { previous() }
        binding.playPause.setOnClickListener { playPause() }
        binding.next.setOnClickListener { next() }
    }

    /** Only one primary action per screen: driving mode demotes this one when "Ya estacioné" appears. */
    fun setPlayPrimary(primary: Boolean) {
        binding.playPause.setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        ImageViewCompat.setImageTintList(
            binding.playPause,
            ContextCompat.getColorStateList(context, if (primary) R.color.background else R.color.ink),
        )
    }

    fun setPlaying(playing: Boolean) {
        binding.playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPause.contentDescription =
            context.getString(if (playing) R.string.transport_pause else R.string.transport_play)
    }
}

/** Volume down, current level, volume up and, where there is room, mute. */
class VolumeControlsView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewVolumeControlsBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        context.withStyledAttributes(attrs, R.styleable.VolumeControlsView) {
            val buttonHeight = getDimensionPixelSize(
                R.styleable.VolumeControlsView_volumeHeight,
                resources.getDimensionPixelSize(R.dimen.touch_min),
            )
            val buttonWidth = getDimensionPixelSize(R.styleable.VolumeControlsView_volumeWidth, LayoutParams.WRAP_CONTENT)
            listOf(binding.down, binding.up).forEach {
                it.updateLayoutParams { width = buttonWidth; height = buttonHeight }
            }
            binding.mute.updateLayoutParams { height = buttonHeight }
            binding.mute.isVisible = getBoolean(R.styleable.VolumeControlsView_volumeShowMute, false)
        }
    }

    fun setActions(down: () -> Unit, up: () -> Unit, toggleMute: (() -> Unit)? = null) {
        binding.down.setOnClickListener { down() }
        binding.up.setOnClickListener { up() }
        toggleMute?.let { action -> binding.mute.setOnClickListener { action() } }
    }

    fun render(playback: PlaybackUi) {
        binding.value.setText(
            when {
                playback.muted -> context.getString(R.string.volume_muted)
                playback.volumePercent == null -> context.getString(R.string.volume_unknown)
                else -> context.getString(R.string.volume_percent, playback.volumePercent)
            },
        )
        binding.mute.setText(if (playback.muted) R.string.volume_unmute else R.string.volume_mute)
    }
}

/** Fixed strip with GPS, movement, time and the sample-data label. */
class TopBarView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewTopBarBinding.inflate(LayoutInflater.from(context), this)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_top_bar)
    }

    fun render(state: ShellViewState) {
        binding.gps.setText(
            when (state.gps) {
                GpsStatus.Good -> R.string.gps_good
                GpsStatus.Searching -> R.string.gps_searching
                GpsStatus.Off -> R.string.gps_off
                GpsStatus.NoPermission -> R.string.gps_no_permission
            },
        )
        binding.gps.setTextColor(color(if (state.gps == GpsStatus.Good) R.color.ink_faint else R.color.signal))
        binding.motion.setText(
            when (state.motion) {
                MotionStatus.Stopped -> R.string.motion_stopped
                MotionStatus.Moving -> R.string.motion_moving
                MotionStatus.Unknown -> R.string.motion_unknown
            },
        )
        binding.clock.text = state.clockText
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)
}

/** Left navigation. The active row has a bar, a surface and a heavier weight, never only a color. */
class RailView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private val binding = ViewRailBinding.inflate(LayoutInflater.from(context), this)
    private val items: Map<Destination, Button> = mapOf(
        Destination.Home to binding.home,
        Destination.Trips to binding.trips,
        Destination.Maintenance to binding.maintenance,
        Destination.Expenses to binding.expenses,
        Destination.Music to binding.music,
        Destination.Parked to binding.parked,
        Destination.Settings to binding.settings,
    )
    private val regular = ResourcesCompat.getFont(context, R.font.space_grotesk_regular)
    private val medium = ResourcesCompat.getFont(context, R.font.space_grotesk_medium)

    init {
        orientation = VERTICAL
        setPadding(0, 0, resources.getDimensionPixelSize(R.dimen.line), 0)
        setBackgroundResource(R.drawable.bg_rail)
        dividerDrawable = ContextCompat.getDrawable(context, R.drawable.rail_divider)
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    fun setOnSelect(onSelect: (Destination) -> Unit) {
        items.forEach { (destination, item) -> item.setOnClickListener { onSelect(destination) } }
    }

    fun select(destination: Destination) {
        items.forEach { (candidate, item) ->
            val active = candidate == destination
            item.isSelected = active
            item.typeface = if (active) medium else regular
            item.contentDescription = if (active) context.getString(R.string.nav_current, item.text) else item.text
        }
    }
}
