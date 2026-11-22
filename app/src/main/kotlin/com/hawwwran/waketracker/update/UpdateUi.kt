package com.hawwwran.waketracker.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.ui.Amber
import com.hawwwran.waketracker.ui.LocalPalette
import com.hawwwran.waketracker.ui.OnAmber

/**
 * The bar across the top while a newer version is waiting. It reaches up under the status
 * bar; the caller keeps the rest of the screen from padding for that bar a second time.
 */
@Composable
fun UpdateBar(state: Updater.State, onClick: () -> Unit) {
    val release = state.release() ?: return
    val text = when (state) {
        is Updater.State.Downloading -> stringResource(R.string.update_bar_downloading, release.version, (state.fraction * 100).toInt())
        is Updater.State.Ready -> stringResource(R.string.update_bar_ready, release.version)
        else -> stringResource(R.string.update_bar_available, release.version)
    }
    Surface(color = Amber, contentColor = OnAmber, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** What the bar opens: the release's notes and the one button that moves the update on. */
@Composable
fun UpdateDialog(state: Updater.State, updater: Updater, onClose: () -> Unit) {
    val release = state.release()
    if (release == null) {
        // The update was installed, or withdrawn, while the dialog was open.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_title, release.version)) },
        text = {
            Column {
                when (state) {
                    is Updater.State.Downloading -> {
                        Text(stringResource(R.string.update_downloading))
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            color = palette.lit,
                            trackColor = palette.hairline,
                        )
                    }

                    is Updater.State.Ready -> Text(
                        stringResource(if (state.waitingForPermission) R.string.update_needs_permission else R.string.update_ready),
                    )

                    is Updater.State.DownloadFailed -> Text(stringResource(R.string.update_download_failed))

                    else -> if (release.notes.isNotEmpty()) {
                        Text(release.notes, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                    }
                }
                Text(
                    stringResource(R.string.update_keeps_record),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            when (state) {
                is Updater.State.Downloading -> TextButton(onClick = updater::cancelDownload) { Text(stringResource(R.string.update_cancel)) }
                is Updater.State.Ready -> TextButton(onClick = updater::install) { Text(stringResource(R.string.update_install_ready)) }
                is Updater.State.DownloadFailed -> TextButton(onClick = updater::downloadAndInstall) { Text(stringResource(R.string.update_try_again)) }
                else -> TextButton(onClick = updater::downloadAndInstall) { Text(stringResource(R.string.update_install)) }
            }
        },
        dismissButton = {
            if (state !is Updater.State.Downloading) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.update_later)) }
            }
        },
        containerColor = palette.track,
        titleContentColor = palette.ink,
        textContentColor = palette.ink,
    )
}

/** On the status page: ask now, and what the answer was. */
@Composable
fun UpdateCheck(updater: Updater) {
    val check by updater.check.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    OutlinedButton(onClick = updater::checkNow, enabled = check != Updater.Check.RUNNING, modifier = Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.check_for_updates))
    }
    val message = when (check) {
        Updater.Check.RUNNING -> stringResource(R.string.check_running)
        Updater.Check.UP_TO_DATE -> stringResource(R.string.check_up_to_date)
        Updater.Check.FOUND -> state.release()?.let { stringResource(R.string.update_bar_available, it.version) }
        Updater.Check.UNREACHABLE -> stringResource(R.string.check_unreachable)
        null -> null
    }
    if (message != null) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.muted, modifier = Modifier.padding(top = 8.dp))
    }
}
