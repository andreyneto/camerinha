package dev.andreyneto.camerinha

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Fullscreen camera switch: "2 / 4" eyebrow over the camera name, between left/right chevrons. */
@Composable
fun SwitchBanner(name: String, position: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(Tones.videoScrim, MaterialTheme.shapes.large)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_left),
            contentDescription = null,
            tint = c.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = position,
                style = MaterialTheme.typography.labelMedium,
                color = c.primary,
                maxLines = 1,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = c.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}
