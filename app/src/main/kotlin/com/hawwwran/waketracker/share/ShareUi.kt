package com.hawwwran.waketracker.share

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.hawwwran.waketracker.R
import com.hawwwran.waketracker.ui.Explanation
import com.hawwwran.waketracker.ui.Formats
import com.hawwwran.waketracker.ui.LocalPalette
import com.hawwwran.waketracker.ui.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sharing part of the settings page: the relay this phone uploads to, its pairing code,
 * and the phones it follows.
 */
@Composable
fun SharingSection(sharing: Sharing, formats: Formats) {
    val state by sharing.state.collectAsStateWithLifecycle()

    Section(stringResource(R.string.sharing_heading))
    Explanation(stringResource(R.string.sharing_explained), top = 0)

    ServerField(state, sharing)
    Text(
        uploadStatus(state, formats),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalPalette.current.muted,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (state.server != null) OwnCode(sharing)

    Section(stringResource(R.string.paired_heading))
    if (state.paired.isEmpty()) Explanation(stringResource(R.string.paired_none), top = 0)
    for (device in state.paired) PairedRow(device, sharing, formats)
    PairButton(sharing)
}

@Composable
private fun ServerField(state: Sharing.State, sharing: Sharing) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var typed by rememberSaveable(state.server) { mutableStateOf(state.server.orEmpty()) }
    var asking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Sharing.ServerResult?>(null) }
    var confirmStop by remember { mutableStateOf(false) }

    fun save() {
        focus.clearFocus()
        // Emptying the field switches sharing off, and that deletes on the relay: ask first.
        if (typed.isBlank() && state.server != null) {
            confirmStop = true
            return
        }
        asking = true
        scope.launch {
            result = withContext(Dispatchers.IO) { sharing.setServer(typed) }
            asking = false
        }
    }

    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            result = null
        },
        label = { Text(stringResource(R.string.sharing_server)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = ::save, enabled = !asking && typed.trim() != state.server.orEmpty()) {
            Text(stringResource(R.string.save))
        }
        val message = when {
            asking -> R.string.sharing_asking
            result == Sharing.ServerResult.INVALID -> R.string.sharing_invalid
            result == Sharing.ServerResult.UNREACHABLE -> R.string.sharing_unreachable
            result == Sharing.ServerResult.NOT_A_RELAY -> R.string.sharing_not_a_relay
            else -> null
        }
        if (message != null) {
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.ink)
        }
    }
    if (confirmStop) {
        Confirm(
            text = stringResource(R.string.sharing_stop_confirm),
            action = stringResource(R.string.sharing_stop),
            onDismiss = {
                confirmStop = false
                typed = state.server.orEmpty()
            },
            onConfirm = { scope.launch(Dispatchers.IO) { sharing.stopSharing() } },
        )
    }
}

@Composable
private fun uploadStatus(state: Sharing.State, formats: Formats): String {
    if (state.server == null) return stringResource(R.string.sharing_off)
    val sent = if (state.lastUploadAt == 0L) {
        stringResource(R.string.sharing_on_never)
    } else {
        stringResource(R.string.sharing_on_sent, formats.dateTime(state.lastUploadAt))
    }
    val waiting = if (state.pendingDays == 0) "" else " " + pluralStringResource(R.plurals.sharing_pending, state.pendingDays, state.pendingDays)
    val trouble = when (state.failure) {
        null -> ""
        RelayException.Reason.UNREACHABLE -> " " + stringResource(R.string.sharing_failure_unreachable)
        RelayException.Reason.FULL -> " " + stringResource(R.string.sharing_failure_full)
        else -> " " + stringResource(R.string.sharing_failure_other)
    }
    return sent + waiting + trouble
}

@Composable
private fun OwnCode(sharing: Sharing) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<PairingCode?>(null) }
    var confirmNew by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    Button(onClick = { shown = sharing.pairingCode() }, modifier = Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.sharing_show_code))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { confirmNew = true }) { Text(stringResource(R.string.sharing_new_code)) }
        TextButton(onClick = { confirmStop = true }) { Text(stringResource(R.string.sharing_stop)) }
    }

    shown?.let { code ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(stringResource(R.string.sharing_code_title)) },
            text = {
                Column {
                    QrCode(code.text(), stringResource(R.string.sharing_code_title))
                    Text(stringResource(R.string.sharing_code_text), modifier = Modifier.padding(top = 16.dp))
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.done)) } },
            containerColor = LocalPalette.current.track,
            titleContentColor = LocalPalette.current.ink,
            textContentColor = LocalPalette.current.ink,
        )
    }
    if (confirmNew) {
        Confirm(
            text = stringResource(R.string.sharing_new_code_confirm),
            action = stringResource(R.string.sharing_new_code),
            onDismiss = { confirmNew = false },
            onConfirm = { scope.launch(Dispatchers.IO) { sharing.newCode() } },
        )
    }
    if (confirmStop) {
        Confirm(
            text = stringResource(R.string.sharing_stop_confirm),
            action = stringResource(R.string.sharing_stop),
            onDismiss = { confirmStop = false },
            onConfirm = { scope.launch(Dispatchers.IO) { sharing.stopSharing() } },
        )
    }
}

/** Black on white whatever the theme, with the quiet border a scanner needs. */
@Composable
private fun QrCode(text: String, description: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp)
            .semantics { contentDescription = description },
    ) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                // A hair over the cell's size: without it rounding leaves light seams between cells.
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun PairedRow(device: PairedDevice, sharing: Sharing, formats: Formats) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(device.name, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
        for (line in syncLines(device, formats)) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
            TextButton(onClick = { removing = true }) { Text(stringResource(R.string.unpair)) }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.rename),
            initial = device.name,
            action = stringResource(R.string.save),
            onDismiss = { renaming = false },
            onSave = { name -> sharing.rename(device.deviceId, name) },
        )
    }
    if (removing) {
        Confirm(
            text = stringResource(R.string.unpair_confirm, device.name),
            action = stringResource(R.string.unpair),
            onDismiss = { removing = false },
            onConfirm = { scope.launch(Dispatchers.IO) { sharing.unpair(device.deviceId) } },
        )
    }
}

/**
 * The two halves of the way a record takes, each with its last time: from that phone to the
 * relay (by that phone's clock), and from the relay to this phone.
 */
@Composable
fun syncLines(device: PairedDevice, formats: Formats): List<String> = buildList {
    if (device.gone) add(stringResource(R.string.paired_gone))
    add(
        if (device.sentAt == 0L) {
            stringResource(R.string.paired_sent_never)
        } else {
            stringResource(R.string.paired_sent, formats.dateTime(device.sentAt))
        },
    )
    add(
        if (device.fetchedAt == 0L) {
            stringResource(R.string.paired_fetched_never)
        } else {
            stringResource(R.string.paired_fetched, formats.dateTime(device.fetchedAt))
        },
    )
}

@Composable
private fun PairButton(sharing: Sharing) {
    val context = LocalContext.current
    var outcome by remember { mutableStateOf<Int?>(null) }
    var scanned by remember { mutableStateOf<PairingCode?>(null) }
    OutlinedButton(
        onClick = {
            outcome = null
            scanCode(
                context,
                onCode = { text ->
                    when (val scan = sharing.read(text)) {
                        is Sharing.Scan.Code -> scanned = scan.code
                        Sharing.Scan.NotACode -> outcome = R.string.pair_not_a_code
                        Sharing.Scan.OwnCode -> outcome = R.string.pair_own_code
                    }
                },
                onFailure = { outcome = R.string.pair_scan_failed },
            )
        },
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(stringResource(R.string.pair_another))
    }
    outcome?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = LocalPalette.current.ink, modifier = Modifier.padding(top = 8.dp))
    }
    scanned?.let { code ->
        NameDialog(
            title = stringResource(R.string.pair_name_title),
            initial = code.name,
            action = stringResource(R.string.pair_action),
            onDismiss = { scanned = null },
            onSave = { name ->
                sharing.pair(code, name)
                outcome = R.string.pair_paired
            },
        )
    }
}

/** Asks for the name this phone's owner gives another phone: when pairing, and when renaming. */
@Composable
private fun NameDialog(title: String, initial: String, action: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val palette = LocalPalette.current
    // Opens ready to type: the field focused, the cursor after the name that is suggested.
    var typed by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        if (typed.text.isBlank()) return
        onSave(typed.text)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { if (it.text.length <= PairingCode.MOST_NAME) typed = it },
                label = { Text(stringResource(R.string.pair_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = ::save, enabled = typed.text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = palette.track,
        titleContentColor = palette.ink,
        textContentColor = palette.ink,
    )
}

/**
 * Reads a QR code with Google Play services' own scanner screen. The app sees only the
 * code's text and holds no camera permission. Play services fetches the scanner the first
 * time any app on the phone asks for it; until then a scan fails and can be tried again.
 * Neither callback runs when the owner backs out.
 */
private fun scanCode(context: Context, onCode: (String) -> Unit, onFailure: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code -> code.rawValue?.let(onCode) }
        .addOnFailureListener { onFailure() }
}

@Composable
private fun Confirm(text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = palette.track,
        textContentColor = palette.ink,
    )
}
