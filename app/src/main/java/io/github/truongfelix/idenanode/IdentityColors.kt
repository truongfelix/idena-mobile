package io.github.truongfelix.idenanode

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The color of each identity state, readable on the theme's background (lighter in the dark theme):
 * Human gold, Verified blue, Newbie green, Candidate teal, Invite purple, Suspended orange, Zombie red,
 * Killed brown; Not validated stays grey.
 */
@Composable
fun statusPalette(): (String) -> Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    return { state ->
        fun pick(light: Long, night: Long) = Color(if (dark) night else light)
        when (state) {
            "Human" -> pick(0xFFB8860B, 0xFFFFD54F)
            "Verified" -> pick(0xFF1565C0, 0xFF82B1FF)
            "Newbie" -> pick(0xFF2E7D32, 0xFF81C784)
            "Candidate" -> pick(0xFF00838F, 0xFF4DD0E1)
            "Invite" -> pick(0xFF7B1FA2, 0xFFCE93D8)
            "Suspended" -> pick(0xFFE65100, 0xFFFFB74D)
            "Zombie" -> pick(0xFFC62828, 0xFFEF9A9A)
            "Killed" -> pick(0xFF5D4037, 0xFFBCAAA4)
            else -> grey
        }
    }
}
