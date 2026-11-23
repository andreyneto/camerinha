package dev.andreyneto.camerinha

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberInitialFocus(vararg keys: Any?): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(*keys) { runCatching { requester.requestFocus() } }
    return requester
}

/** Where settings opens: its list, or straight into discovery / a new camera (from the empty screen). */
enum class SettingsStart { List, Discovery, NewCamera }

/** Button content: 20dp icon + label. */
@Composable
private fun IconLabel(@DrawableRes icon: Int, text: String) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    ButtonText(text)
}

@Composable
fun EmptyScreen(onSearch: () -> Unit, onAdd: () -> Unit) {
    val focus = rememberInitialFocus()
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = Safe.horizontal, vertical = Safe.vertical),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                icon = R.drawable.ic_videocam,
                title = stringResource(R.string.empty_title),
                body = stringResource(R.string.empty_body),
                centered = true,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryPill(onClick = onSearch, modifier = Modifier.focusRequester(focus)) {
                    IconLabel(R.drawable.ic_search, stringResource(R.string.search_network))
                }
                PillButton(onClick = onAdd) { IconLabel(R.drawable.ic_add, stringResource(R.string.add_manually)) }
            }
        }
    }
}

private const val ID_SEARCH = "action:search"
private const val ID_ADD = "action:add"
private const val ID_SHORTCUT = "action:shortcut"

/**
 * Camera list, discovery and the camera form, one at a time. The remote's Back leaves the form or
 * discovery for the list (or leaves settings, when it was opened straight into them).
 * @param onExit leaves settings.
 * @param onStartConsumed called once settings has left [start] for its list: re-opening settings later (the
 *   composition is dropped while the activity is stopped) then shows the list instead of the starting mode.
 */
@Composable
fun SettingsScreen(
    cameras: List<Camera>,
    onChange: (List<Camera>) -> Unit,
    start: SettingsStart = SettingsStart.List,
    onExit: () -> Unit,
    onStartConsumed: () -> Unit = {},
) {
    // null = list; otherwise the camera being created or edited.
    var editing by remember { mutableStateOf(if (start == SettingsStart.NewCamera) Camera(name = "", url = "") else null) }
    var discovering by remember { mutableStateOf(start == SettingsStart.Discovery) }
    // Opened from the empty screen straight into discovery or the form: Back goes back there.
    var exitOnBack by remember { mutableStateOf(start != SettingsStart.List) }
    // The list is disposed while the form or discovery shows; this brings focus back to the same row.
    var lastFocusedId by remember { mutableStateOf<String?>(null) }
    // Where Left / Right land when crossing between the actions pane and the list.
    var lastActionId by remember { mutableStateOf(ID_SEARCH) }
    var lastRowId by remember { mutableStateOf<String?>(null) }
    val current = editing

    // From here on Back returns to the list, and the activity re-opens settings on the list.
    fun leaveStart() {
        if (exitOnBack) {
            exitOnBack = false
            onStartConsumed()
        }
    }

    fun backToList() {
        if (exitOnBack) {
            onExit()
        } else {
            editing = null
            discovering = false
        }
    }

    if (current != null) {
        val isNew = cameras.none { it.id == current.id }
        BackHandler { backToList() }
        CameraForm(
            camera = current,
            isNew = isNew,
            onEdit = { editing = it },
            onSave = {
                onChange(if (isNew) cameras + current else cameras.map { if (it.id == current.id) current else it })
                lastFocusedId = current.id
                leaveStart()
                editing = null
            },
            onDelete = {
                onChange(cameras.filterNot { it.id == current.id })
                lastFocusedId = null
                leaveStart()
                editing = null
            },
            onCancel = { backToList() },
        )
        return
    }

    if (discovering) {
        BackHandler { backToList() }
        DiscoveryScreen(
            onFound = {
                discovering = false
                // Back from this form returns to the list, not out of settings.
                leaveStart()
                editing = it
            },
        )
        return
    }

    DisposableEffect(Unit) { onDispose { Shortcut.learning = false } }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun valid(id: String?) = id != null && (id.startsWith("action:") || cameras.any { it.id == id })
    val firstRowId = cameras.firstOrNull()?.id ?: ID_SHORTCUT

    fun Modifier.tracked(id: String) = focusRequester(requester(id)).onFocusChanged {
        if (it.isFocused) {
            lastFocusedId = id
            if (id == ID_SEARCH || id == ID_ADD) lastActionId = id else lastRowId = id
        }
    }
    // Left from any row: the last focused action (Search by default). Up/Down stop at the list's ends.
    fun Modifier.row(id: String) = tracked(id).focusProperties {
        left = requester(lastActionId)
        if (id == firstRowId) up = FocusRequester.Cancel
        if (id == ID_SHORTCUT) down = FocusRequester.Cancel
    }
    // Right from an action: the last focused row, or the first one. Up/Down stop at the actions' ends.
    fun Modifier.action(id: String) = tracked(id).focusProperties {
        right = requester(lastRowId?.takeIf(::valid) ?: firstRowId)
        if (id == ID_SEARCH) up = FocusRequester.Cancel
        if (id == ID_ADD) down = FocusRequester.Cancel
    }
    LaunchedEffect(Unit) {
        val target = lastFocusedId?.takeIf(::valid) ?: cameras.firstOrNull()?.id ?: ID_SEARCH
        runCatching { requester(target).requestFocus() }
    }

    TwoPane(
        title = stringResource(R.string.settings_title),
        body = stringResource(R.string.settings_body),
        actions = {
            PrimaryPill(onClick = { discovering = true }, modifier = Modifier.fillMaxWidth().action(ID_SEARCH)) {
                IconLabel(R.drawable.ic_search, stringResource(R.string.search_network))
                Spacer(Modifier.weight(1f))
            }
            PillButton(onClick = { editing = Camera(name = "", url = "") }, modifier = Modifier.fillMaxWidth().action(ID_ADD)) {
                IconLabel(R.drawable.ic_add, stringResource(R.string.add_manually))
                Spacer(Modifier.weight(1f))
            }
        },
    ) {
        Section(R.drawable.ic_videocam, stringResource(R.string.section_cameras)) {
            if (cameras.isEmpty()) InfoText(stringResource(R.string.cameras_none))
            cameras.forEach { camera ->
                SettingsRow(
                    icon = R.drawable.ic_videocam,
                    label = camera.name,
                    value = maskPassword(camera.url),
                    onClick = { editing = camera },
                    modifier = Modifier.row(camera.id),
                )
            }
        }
        Section(R.drawable.ic_settings_remote, stringResource(R.string.section_remote)) {
            SettingsRow(
                icon = R.drawable.ic_settings_remote,
                label = stringResource(R.string.shortcut_label),
                value = when {
                    !Shortcut.connected -> stringResource(R.string.shortcut_unavailable)
                    Shortcut.learning -> stringResource(R.string.shortcut_learning)
                    Shortcut.keyCode == 0 -> stringResource(R.string.shortcut_unset)
                    else -> stringResource(R.string.shortcut_set, KeyEvent.keyCodeToString(Shortcut.keyCode).removePrefix("KEYCODE_"))
                },
                onClick = { if (Shortcut.connected) Shortcut.learning = !Shortcut.learning },
                modifier = Modifier.row(ID_SHORTCUT),
                active = Shortcut.learning,
            )
        }
        // No on-screen Back: the remote's Back button closes settings.
    }
}

/** Finds ONVIF cameras on the LAN; picking one asks for its login and fills in the stream URLs. */
@Composable
private fun DiscoveryScreen(onFound: (Camera) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<OnvifDevice>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    // Neutral progress ("Asking 192.168.0.10…") and what went wrong, shown separately.
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Starts from the login in local.properties (onvif.user / onvif.password). Cameras in one
    // house usually share a login, so whatever is typed is kept for the next one.
    var username by remember { mutableStateOf(BuildConfig.ONVIF_USER) }
    var password by remember { mutableStateOf(BuildConfig.ONVIF_PASSWORD) }

    LaunchedEffect(round) {
        status = null
        error = null
        devices = null
        devices = withContext(Dispatchers.IO) { runCatching { Onvif.discover(context) }.getOrDefault(emptyList()) }
    }

    fun connect(device: OnvifDevice) {
        error = null
        status = context.getString(R.string.querying, device.host)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Onvif.streams(device, username, password) } }
            status = null
            result.onSuccess { streams ->
                val main = streams.first().url
                onFound(
                    Camera(
                        name = "${device.name} ${device.host.substringAfterLast('.')}",
                        url = main,
                        subUrl = streams.lighterThan(main)?.url.orEmpty(),
                        streams = streams,
                    )
                )
            }.onFailure {
                val reason = (it as? OnvifException)?.let { e -> context.getString(e.messageId, *e.args) }
                    ?: it.message ?: context.getString(R.string.failed)
                error = "${device.host}: $reason"
            }
        }
    }

    val again = rememberInitialFocus()
    val firstDevice = remember { FocusRequester() }
    // Results arrived: OK on the first camera is the likely next step.
    LaunchedEffect(devices) { if (!devices.isNullOrEmpty()) runCatching { firstDevice.requestFocus() } }

    TwoPane(title = stringResource(R.string.search_network), body = stringResource(R.string.discovery_body)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val list = devices
            when {
                list == null -> PlaceholderRow(R.drawable.ic_search, stringResource(R.string.searching))
                list.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_search,
                    title = stringResource(R.string.none_found),
                    body = stringResource(R.string.none_found_body),
                    badge = false,
                )
                else -> list.forEachIndexed { i, device ->
                    SettingsRow(
                        icon = R.drawable.ic_videocam,
                        label = device.name,
                        value = device.host,
                        modifier = if (i == 0) Modifier.focusRequester(firstDevice) else Modifier,
                        onClick = {
                            promptText(context, context.getString(R.string.username_for, device.host), username, uri = true) { user ->
                                username = user
                                promptText(context, context.getString(R.string.password_for, device.host), password, uri = true) { pass ->
                                    password = pass
                                    connect(device)
                                }
                            }
                        },
                    )
                }
            }
            status?.let { InfoText(it) }
            error?.let { ErrorNote(it) }
        }
        // No on-screen Back: the remote's Back button returns to settings.
        Row {
            PillButton(onClick = { round++ }, modifier = Modifier.focusRequester(again)) {
                IconLabel(R.drawable.ic_search, stringResource(R.string.search_again))
            }
        }
    }
}

@Composable
private fun CameraForm(
    camera: Camera,
    isNew: Boolean,
    onEdit: (Camera) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = rememberInitialFocus()
    val valid = camera.name.isNotBlank() && Regex("rtsps?://.+").matches(camera.url)
    // The find below finishes later: apply its result to whatever the form holds by then.
    val latest by rememberUpdatedState(camera)
    var finding by remember { mutableStateOf(false) }
    var findStatus by remember { mutableStateOf<String?>(null) }
    var findError by remember { mutableStateOf<String?>(null) }
    // Save is the natural next step after the fields; D-pad geometry alone would pick Cancel.
    val saveFocus = remember { FocusRequester() }
    val toSave = if (valid) saveFocus else FocusRequester.Default
    // "Find streams" disappears when it succeeds; without this, focus falls back to the first field.
    var focusSave by remember { mutableStateOf(false) }
    LaunchedEffect(focusSave) {
        if (focusSave) {
            runCatching { saveFocus.requestFocus() }
            focusSave = false
        }
    }

    fun typeMain() = promptText(context, context.getString(R.string.prompt_url), camera.url.ifBlank { "rtsp://" }, uri = true) { onEdit(latest.copy(url = it)) }
    fun typeSub() = promptText(context, context.getString(R.string.prompt_sub), camera.subUrl.ifBlank { "rtsp://" }, uri = true) { onEdit(latest.copy(subUrl = if (it == "rtsp://") "" else it)) }

    fun findStreams() {
        val url = camera.url
        val host = RtspAddress.parse(url)?.host ?: return
        finding = true
        findError = null
        findStatus = context.getString(R.string.finding_streams, host)
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { StreamFinder.find(url) } }
            finding = false
            result.onSuccess { streams ->
                val now = latest
                if (now.url != url) {
                    findStatus = null
                    return@onSuccess
                }
                val sub = now.subUrl.ifBlank { streams.lighterThan(url)?.url.orEmpty() }
                findStatus = context.getString(R.string.streams_found, streams.size)
                onEdit(now.copy(streams = streams, subUrl = sub))
                focusSave = true
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                findStatus = null
                val reason = (it as? OnvifException)?.let { e -> context.getString(e.messageId, *e.args) }
                    ?: it.message ?: context.getString(R.string.failed)
                findError = "$host: $reason"
            }
        }
    }
    TwoPane(
        title = stringResource(if (isNew) R.string.new_camera else R.string.edit_camera),
        body = camera.name.ifBlank { null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldRow(
                label = stringResource(R.string.field_name),
                value = camera.name.ifBlank { stringResource(R.string.name_placeholder) },
                placeholder = camera.name.isBlank(),
                modifier = Modifier.focusRequester(focus),
                onClick = { promptText(context, context.getString(R.string.field_name), camera.name, uri = false) { onEdit(camera.copy(name = it)) } },
            )
            val options = camera.streams
            FieldRow(
                label = stringResource(R.string.field_url),
                choice = options.isNotEmpty(),
                value = streamText(context, options, camera.url).ifBlank { stringResource(R.string.url_placeholder) },
                placeholder = camera.url.isBlank(),
                onClick = {
                    if (options.isEmpty()) typeMain()
                    else chooseOne(
                        context,
                        title = context.getString(R.string.prompt_url),
                        items = options.map { describeStream(context, it) } + context.getString(R.string.stream_type_address),
                        checked = options.indexOfFirst { it.url == camera.url },
                    ) { i -> if (i < options.size) onEdit(latest.copy(url = options[i].url)) else typeMain() }
                },
            )
            val findShown = options.isEmpty() && Regex("rtsps?://.+").matches(camera.url)
            FieldRow(
                modifier = Modifier.focusProperties { if (!findShown) down = toSave },
                label = stringResource(R.string.field_sub),
                choice = options.isNotEmpty(),
                value = streamText(context, options, camera.subUrl).ifBlank { stringResource(R.string.sub_placeholder) },
                placeholder = camera.subUrl.isBlank(),
                onClick = {
                    if (options.isEmpty()) typeSub()
                    else chooseOne(
                        context,
                        title = context.getString(R.string.prompt_sub),
                        items = listOf(context.getString(R.string.stream_none)) +
                            options.map { describeStream(context, it) } +
                            context.getString(R.string.stream_type_address),
                        checked = if (camera.subUrl.isBlank()) 0 else options.indexOfFirst { it.url == camera.subUrl }.let { if (it < 0) -1 else it + 1 },
                    ) { i ->
                        when {
                            i == 0 -> onEdit(latest.copy(subUrl = ""))
                            i <= options.size -> onEdit(latest.copy(subUrl = options[i - 1].url))
                            else -> typeSub()
                        }
                    }
                },
            )
            if (findShown) {
                SettingsRow(
                    modifier = Modifier.focusProperties { down = toSave },
                    icon = R.drawable.ic_search,
                    label = stringResource(R.string.find_streams),
                    value = stringResource(R.string.find_streams_body),
                    onClick = { if (!finding) findStreams() },
                )
            }
            findStatus?.let { InfoText(it) }
            findError?.let { ErrorNote(it) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!valid) InfoText(stringResource(R.string.form_incomplete))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryPill(onClick = onSave, enabled = valid, modifier = Modifier.focusRequester(saveFocus)) {
                    ButtonText(stringResource(R.string.save))
                }
                PillButton(onClick = onCancel) { ButtonText(stringResource(R.string.cancel)) }
            }
        }
        if (!isNew) {
            // Two-step: the first OK arms it, the second deletes; moving away disarms it.
            var armed by remember { mutableStateOf(false) }
            Row {
                PillButton(
                    onClick = { if (armed) onDelete() else armed = true },
                    destructive = true,
                    modifier = Modifier.onFocusChanged { if (!it.isFocused) armed = false },
                ) {
                    IconLabel(R.drawable.ic_delete, stringResource(if (armed) R.string.delete_confirm else R.string.delete))
                }
            }
        }
    }
}

/** A field's value: the stream's description when it is one of [options], else the URL without its password. */
private fun streamText(context: Context, options: List<StreamOption>, url: String): String =
    options.firstOrNull { it.url == url }?.let { describeStream(context, it) } ?: maskPassword(url)

/** "1920 × 1080 · av_stream/ch0", plus "detected" for guessed URLs. Never shows the password. */
private fun describeStream(context: Context, option: StreamOption): String {
    val path = RtspAddress.parse(option.url)?.rest?.removePrefix("/")?.ifEmpty { "/" } ?: maskPassword(option.url)
    return listOfNotNull(
        if (option.area > 0) "${option.width} × ${option.height}" else null,
        path,
        if (option.label == StreamOption.DETECTED) context.getString(R.string.stream_detected) else null,
    ).joinToString(" · ")
}

/** Native single-choice list (D-pad friendly, focus starts on [checked]); OK on a row picks it. */
private fun chooseOne(context: Context, title: String, items: List<String>, checked: Int, onChoose: (Int) -> Unit) {
    val dialog = AlertDialog.Builder(context, R.style.ThemeOverlay_Camerinha_Dialog_List)
        .setTitle(title)
        .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
            d.dismiss()
            onChoose(which)
        }
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.show()
    if (checked >= 0) dialog.listView?.setSelection(checked)
}

/** A native dialog: the most dependable way to get the TV keyboard (or the phone remote's) on screen. */
private fun promptText(context: Context, title: String, initial: String, uri: Boolean, onResult: (String) -> Unit) {
    val builder = AlertDialog.Builder(context, R.style.ThemeOverlay_Camerinha_Dialog)
    // Inflate against the dialog theme so the field picks up its colours and font.
    val themed = builder.context
    val density = themed.resources.displayMetrics.density
    fun px(dp: Int) = (dp * density).toInt()
    val input = EditText(themed).apply {
        setText(initial)
        setSelection(initial.length)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (uri) InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = themed.getDrawable(R.drawable.field_background)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }
    val frame = FrameLayout(themed).apply {
        setPadding(px(24), px(12), px(24), px(4))
        addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val dialog = builder
        .setTitle(title)
        .setView(frame)
        .setPositiveButton(android.R.string.ok) { _, _ -> onResult(input.text.toString().trim()) }
        .setNegativeButton(R.string.cancel, null)
        .create()
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            onResult(input.text.toString().trim())
            dialog.dismiss()
            true
        } else false
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
    input.requestFocus()
}
