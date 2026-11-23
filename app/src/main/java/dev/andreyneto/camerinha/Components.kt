package dev.andreyneto.camerinha

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val FADE_MS = 180

/** Rows: tonal at rest, primary when focused, primaryContainer when pressed. */
@Composable
fun rowColors() = MaterialTheme.colorScheme.let { c ->
    ClickableSurfaceDefaults.colors(
        containerColor = Tones.containerHigh,
        contentColor = c.onSurface,
        focusedContainerColor = c.primary,
        focusedContentColor = c.onPrimary,
        pressedContainerColor = c.primaryContainer,
        pressedContentColor = c.onPrimaryContainer,
        disabledContainerColor = Tones.containerHigh,
        disabledContentColor = c.onSurface.copy(alpha = 0.38f),
    )
}

/** A 28dp pill following the focus rule; [destructive] turns error-toned when focused. */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
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
            containerColor = Tones.containerHigh,
            contentColor = if (destructive) c.error else c.onSurface,
            focusedContainerColor = if (destructive) c.error else c.primary,
            focusedContentColor = if (destructive) c.onError else c.onPrimary,
            pressedContainerColor = if (destructive) c.errorContainer else c.primaryContainer,
            pressedContentColor = if (destructive) c.onErrorContainer else c.onPrimaryContainer,
            disabledContainerColor = Tones.containerHigh,
            disabledContentColor = c.onSurface.copy(alpha = 0.38f),
        ),
        content = content,
    )
}

/** Button label in the button type style; single line. */
@Composable
fun ButtonText(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Same inset from a grid tile's bottom-left corner for every tile's [StatusChip]. */
val ChipInset = 20.dp

/** [StatusChip] height: labelLarge 20sp line + 2 x 6dp padding (at default font scale). */
val ChipHeight = 32.dp

/**
 * Camera name pill over playing video: live dot + name. Primary when its tile is focused.
 * Only shown while playing; connecting and errors use the centred blocks in [CameraView].
 */
@Composable
fun StatusChip(name: String, focused: Boolean, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val dot = if (focused) c.onPrimary else c.primary
    Row(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(if (focused) c.primary else Tones.videoScrim, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) c.onPrimary else c.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Remote hints over video: one card, each item a separate line of text side by side. Fades in and out. */
@Composable
fun HintCard(items: List<String>, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
    ) {
        Row(
            modifier = Modifier
                .background(Tones.floatingCard, MaterialTheme.shapes.large)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
