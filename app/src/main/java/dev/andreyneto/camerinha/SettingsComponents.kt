package dev.andreyneto.camerinha

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Widest a settings column gets; text blocks stay under 520dp. */
private val ColumnMax = 560.dp
private val TextMax = 520.dp

/** Vertical room above and below the panes; the first/last rows rest inside the overscan safe area (> 27dp). */
private val PaneVertical = 40.dp
/** Height of the fade at the top and bottom edges of the scrolling pane; less than [PaneVertical]. */
private val EdgeFade = 32.dp

/**
 * D-pad scrolling for the right pane: the focused row settles ~30% down the viewport instead of
 * hugging the edge, so the rows around it stay in view. Clamped by the scroll range, so short lists
 * never move and the first rows stay put.
 */
@OptIn(ExperimentalFoundationApi::class)
private val PivotSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = containerSize * 0.3f
        // A row that would run past the bottom from the pivot is aligned to the bottom instead.
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

/** Fades content out over [EdgeFade] at the top and bottom, so rows slide under the edge rather than look sliced. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val f = (EdgeFade.toPx() / size.height).coerceIn(0f, 0.5f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                f to Color.Black,
                1f - f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Settings layout for a 10-foot screen: what a phone puts in a large top app bar sits in a left
 * pane (title + description, then the screen's [actions] stacked as full-width pills); the content
 * scrolls on the right. The right pane spans the full screen height, its vertical room lives inside
 * the scroll content so rows are never clipped at a padded edge. Horizontal padding keeps everything
 * inside the overscan safe area (58dp > 48dp).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TwoPane(
    title: String,
    body: String?,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 58.dp),
    ) {
        Column(
            Modifier.width(320.dp).fillMaxHeight().padding(vertical = PaneVertical),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!body.isNullOrBlank()) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actions != null) {
                Spacer(Modifier.height(16.dp))
                Column(
                    // Up/Down stays among the actions; Left/Right is wired by the screen.
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = actions,
                )
            }
        }
        Spacer(Modifier.width(40.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
                Column(
                    modifier = Modifier
                        .widthIn(max = ColumnMax)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .focusGroup()
                        .fadeEdges()
                        .verticalScroll(rememberScrollState())
                        // Inside the scroll: horizontal room for the focus scale, vertical room so the
                        // first/last rows rest inside the safe area but scroll all the way to the edge.
                        .padding(horizontal = 12.dp, vertical = PaneVertical),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    content = content,
                )
            }
        }
    }
}

/** Section header (20dp primary icon + titleMedium) with its rows 12dp apart underneath. */
@Composable
fun Section(@DrawableRes icon: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

/** Colours for a row that reads as "active" (the shortcut row while it waits for a key), focused or not. */
@Composable
private fun activeRowColors(): ClickableSurfaceColors = MaterialTheme.colorScheme.let { c ->
    ClickableSurfaceDefaults.colors(
        containerColor = c.primaryContainer,
        contentColor = c.onPrimaryContainer,
        focusedContainerColor = c.primaryContainer,
        focusedContentColor = c.onPrimaryContainer,
        pressedContainerColor = c.primaryContainer,
        pressedContentColor = c.onPrimaryContainer,
    )
}

/** The tonal clickable row every settings list is made of. [content] gets whether the row has focus. */
@Composable
private fun TonalRow(
    onClick: () -> Unit,
    modifier: Modifier,
    colors: ClickableSurfaceColors,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.large),
        colors = colors,
        scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.ROW),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) { content(focused) }
    }
}

/** Secondary copy: onSurfaceVariant at rest, the row's own content colour when focused (onPrimary). */
@Composable
private fun supporting(focused: Boolean): Color =
    if (focused) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Leading 24dp icon, titleMedium headline, optional bodyLarge value (one line each).
 * [active] keeps the row primaryContainer, focused or not.
 */
@Composable
fun SettingsRow(
    @DrawableRes icon: Int,
    label: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    TonalRow(onClick, modifier, if (active) activeRowColors() else rowColors()) { focused ->
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) LocalContentColor.current else supporting(focused),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A form field on TV: a tonal row showing the label over the current value. The trailing icon says
 * what OK does: a pencil opens the native text prompt, a drop-down arrow ([choice]) opens a list to
 * pick from. [placeholder] = the value is empty and shows a hint.
 */
@Composable
fun FieldRow(
    label: String,
    value: String,
    placeholder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    choice: Boolean = false,
) {
    TonalRow(onClick, modifier, rowColors()) { focused ->
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = supporting(focused),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (placeholder) supporting(focused) else LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painterResource(if (choice) R.drawable.ic_arrow_drop_down else R.drawable.ic_edit),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Row-shaped, not focusable, recessive: "something is happening here". */
@Composable
fun PlaceholderRow(@DrawableRes icon: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tones.containerLow, MaterialTheme.shapes.large)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Something went wrong: errorContainer card with an icon, never bare red text. */
@Composable
fun ErrorNote(text: String) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .widthIn(max = TextMax)
            .background(c.errorContainer, MaterialTheme.shapes.large)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_error), contentDescription = null, modifier = Modifier.size(24.dp), tint = c.onErrorContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer)
    }
}

/** Neutral one-off line in a settings pane (progress, helper text). */
@Composable
fun InfoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.widthIn(max = TextMax),
    )
}

/** Icon badge + headlineSmall title + onSurfaceVariant body. [badge] 96dp for full screens, smaller inline. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    badge: Boolean = true,
    centered: Boolean = false,
) {
    Column(
        modifier = modifier.widthIn(max = TextMax),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val badgeSize = if (badge) 96.dp else 72.dp
        Box(
            modifier = Modifier
                .size(badgeSize)
                .background(Tones.containerHigh, RoundedCornerShape(if (badge) 32.dp else 24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(badgeSize / 2),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** The one filled action of a screen: primaryContainer at rest, primary when focused. */
@Composable
fun PrimaryPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(MaterialTheme.shapes.extraLarge),
        scale = ButtonDefaults.scale(focusedScale = FocusScale.BUTTON),
        colors = ButtonDefaults.colors(
            containerColor = c.primaryContainer,
            contentColor = c.onPrimaryContainer,
            focusedContainerColor = c.primary,
            focusedContentColor = c.onPrimary,
            pressedContainerColor = c.primaryContainer,
            pressedContentColor = c.onPrimaryContainer,
            disabledContainerColor = Tones.containerHigh,
            disabledContentColor = c.onSurface.copy(alpha = 0.38f),
        ),
        content = content,
    )
}
