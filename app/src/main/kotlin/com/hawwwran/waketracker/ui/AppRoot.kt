package com.hawwwran.waketracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.WakeTrackerApp
import com.hawwwran.waketracker.data.UsageAccess
import com.hawwwran.waketracker.update.UpdateBar
import com.hawwwran.waketracker.update.UpdateCheck
import com.hawwwran.waketracker.update.UpdateDialog
import com.hawwwran.waketracker.update.Updater
import kotlinx.coroutines.delay

/** The record's pages, the status page over them, or the request for usage access while it is missing. */
@Composable
fun AppRoot(model: MainViewModel = viewModel()) {
    val palette = LocalPalette.current
    val hasAccess by model.hasAccess.collectAsStateWithLifecycle()
    val record by model.record.collectAsStateWithLifecycle()
    var statusOpen by rememberSaveable { mutableStateOf(false) }

    val updater = (LocalContext.current.applicationContext as WakeTrackerApp).updater
    val update by updater.state.collectAsStateWithLifecycle()
    var updateOpen by rememberSaveable { mutableStateOf(false) }
    val barShown = update != Updater.State.Idle

    // Reads the log when the app comes to the front (also on the way back from the system
    // settings, where access is granted) and keeps today's figures moving while it is watched.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            updater.onResume()
            updater.checkIfDue()
            while (true) {
                model.refresh()
                delay(REFRESH_EVERY_MS)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(palette.ground)) {
        if (barShown) UpdateBar(update) { updateOpen = true }
        // The bar already sits below the status bar; without this the screen under it would
        // leave room for the status bar a second time.
        val belowBar = if (barShown) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.weight(1f).then(belowBar).windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                // A record that exists stays readable when access is taken away later; the
                // status page then says why it no longer grows.
                !hasAccess && record.coverage == null -> AccessRequest()
                statusOpen -> {
                    BackHandler { statusOpen = false }
                    StatusScreen(model, onBack = { statusOpen = false }, updates = { UpdateCheck(updater) })
                }

                else -> Browser(
                    model = model,
                    needsAttention = !hasAccess || record.failure != null,
                    onOpenStatus = { statusOpen = true },
                )
            }
        }
    }
    if (updateOpen) UpdateDialog(update, updater) { updateOpen = false }
}

@Composable
private fun AccessRequest() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp)) {
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium, color = palette.ink)
        Text(
            stringResource(R.string.access_text),
            style = MaterialTheme.typography.bodyLarge,
            color = palette.muted,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = { UsageAccess.openSettings(context) }, modifier = Modifier.padding(top = 28.dp)) {
            Text(stringResource(R.string.access_open_settings))
        }
        Text(
            stringResource(R.string.access_history_note),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
            modifier = Modifier.padding(top = 28.dp),
        )
    }
}

private const val REFRESH_EVERY_MS = 60_000L
