package com.picfilter.spike

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * The person screen, in Compose, as a measured comparison rather than a
 * migration.
 *
 * The question a reviewer asks about a 2026 Android app built on the View
 * system is "why?", and the honest answer needs one screen done the other way
 * to compare. This is that screen: the smallest in the app, already covered by
 * `RotationTest`, and the only one ported.
 *
 * What is deliberately shared with the rest of the app: the ViewModel, the
 * strings, the colour resources. Compose is doing the drawing and nothing else,
 * because changing two things at once would answer neither question.
 *
 * What the comparison actually showed is written up in TESTING.md; the short
 * version is that the drawing got shorter and the testing got different.
 */

/** The design tokens, read from the same resources the View screens use. */
private fun Context.pk(id: Int) = Color(Ui.color(this, id))

@Composable
fun PersonScreen(
    name: String,
    photos: List<Pair<Long, String>>?,
    spans: Int,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ctx.pk(R.color.pk_background))
            .statusBarsPadding()
    ) {
        TitleBar(name, onBack)

        Text(
            text = when {
                photos == null -> stringResource(R.string.person_loading)
                photos.isEmpty() -> stringResource(R.string.person_none, name)
                else -> pluralStringResource(
                    R.plurals.person_kept_because, photos.size, name, photos.size)
            },
            color = ctx.pk(R.color.pk_on_surface_variant),
            fontSize = 14.sp,
            modifier = Modifier
                .testTag("count")
                .padding(horizontal = Ui.MD.dp, vertical = Ui.XS.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(spans),
            modifier = Modifier.weight(1f).testTag("grid"),
            horizontalArrangement = Arrangement.spacedBy(Ui.GRID_GAP.dp),
            verticalArrangement = Arrangement.spacedBy(Ui.GRID_GAP.dp),
        ) {
            items(photos.orEmpty(), key = { it.first }) { (_, uri) ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(ctx.pk(R.color.pk_surface_variant)),
                )
            }
        }

        // Hidden until the count is known, exactly as the View version is: a
        // Remove button under a grid that has not loaded invites a tap on
        // something nobody has seen yet.
        if (photos != null) {
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Ui.MD.dp)
                    .navigationBarsPadding()
                    .testTag("remove"),
            ) {
                Text(stringResource(R.string.person_remove, name))
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.person_remove_title, name)) },
            // Removing someone is not deleting their photos and the dialog has
            // to say so plainly -- this screen is a grid of pictures with a
            // Remove button under it, which reads the wrong way left alone.
            text = {
                Text(stringResource(
                    R.string.person_remove_body, name, photos?.size ?: 0))
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove() }) {
                    Text(stringResource(R.string.action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TitleBar(title: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(ctx.pk(R.color.pk_surface))
            .padding(horizontal = Ui.XS.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.a11y_back),
                tint = ctx.pk(R.color.pk_on_surface),
            )
        }
        Text(
            text = title,
            color = ctx.pk(R.color.pk_on_surface),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = Ui.XS.dp).testTag("title"),
        )
    }
}
