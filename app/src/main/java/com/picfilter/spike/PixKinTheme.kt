package com.picfilter.spike

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The app's palette, told to Compose.
 *
 * This file is the part of the Compose experiment worth looking at hardest.
 * Every colour here already exists in `res/values/colors.xml` and every View
 * screen reads it through `Ui.color(...)`; a Material 3 component reads
 * `MaterialTheme.colorScheme` instead and knows nothing about either. Without
 * this, the ported screen's OutlinedButton drew in Material's default purple-
 * grey while the text beside it drew in the app's colours -- the screenshot
 * before this was added shows exactly that.
 *
 * So porting one screen also means porting the design system, or running two.
 * The values are read from the same resources rather than repeated, which
 * keeps one source of truth and means dark mode still comes from `values-night`
 * for free.
 */
@Composable
fun PixKinTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(colorScheme = ctx.scheme(), content = content)
}

private fun Context.c(id: Int) = Color(Ui.color(this, id))

private fun Context.scheme() = run {
    val dark = resources.configuration.isNightModeActive
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = c(R.color.pk_primary),
        onPrimary = c(R.color.pk_on_primary),
        primaryContainer = c(R.color.pk_primary_container),
        onPrimaryContainer = c(R.color.pk_on_primary_container),
        secondary = c(R.color.pk_secondary),
        onSecondary = c(R.color.pk_on_secondary),
        secondaryContainer = c(R.color.pk_secondary_container),
        onSecondaryContainer = c(R.color.pk_on_secondary_container),
        background = c(R.color.pk_background),
        onBackground = c(R.color.pk_on_surface),
        surface = c(R.color.pk_surface),
        onSurface = c(R.color.pk_on_surface),
        surfaceVariant = c(R.color.pk_surface_variant),
        onSurfaceVariant = c(R.color.pk_on_surface_variant),
        outline = c(R.color.pk_outline),
        outlineVariant = c(R.color.pk_outline_variant),
    )
}
