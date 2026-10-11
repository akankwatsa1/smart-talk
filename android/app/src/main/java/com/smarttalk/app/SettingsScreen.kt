package com.smarttalk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: ChatViewModel, onDone: () -> Unit) {
    val settings = vm.settings

    var editing by remember { mutableStateOf<ApiAccount?>(null) }
    var creatingFrom by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ApiAccount?>(null) }
    var savedNotice by remember { mutableStateOf(false) }

    var temperature by remember { mutableStateOf(settings.temperature) }
    var topP by remember { mutableStateOf(settings.topP) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var searchMode by remember { mutableStateOf(settings.searchMode) }
    var fetchPages by remember { mutableStateOf(settings.fetchPageContent) }
    var maxResults by remember { mutableStateOf(settings.maxSearchResults.toString()) }
    var maxPages by remember { mutableStateOf(settings.maxPagesToFetch.toString()) }
    var darkTheme by remember { mutableStateOf(settings.darkTheme) }
    var imageSize by remember { mutableStateOf(settings.imageSize) }
    var useDeviceVoice by remember { mutableStateOf(settings.useDeviceVoice) }

    fun persist() {
        vm.updateSettings(
            settings.copy(
                temperature = temperature,
                topP = topP,
                systemPrompt = systemPrompt,
                searchMode = searchMode,
                fetchPageContent = fetchPages,
                maxSearchResults = maxResults.toIntOrNull()?.coerceIn(1, 15) ?: 5,
                maxPagesToFetch = maxPages.toIntOrNull()?.coerceIn(0, 6) ?: 3,
                darkTheme = darkTheme,
                imageSize = imageSize,
                useDeviceVoice = useDeviceVoice
            )
        )
        savedNotice = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                TextButton(onClick = onDone) { Text(stringResource(R.string.back)) }
            }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ===================================================== ACCOUNTS
            SectionHeading("API accounts")
            Text(
                text = "An account is one key with one provider. Add several to spread rate " +
                    "limits, or to use different models for different jobs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            settings.accounts.forEach { account ->
                AccountRow(
                    account = account,
                    isDefaultChat = settings.chatAccount().id == account.id,
                    onEdit = { editing = account },
                    onDelete = { pendingDelete = account },
                    onMakeDefault = {
                        val reordered = listOf(account) + settings.accounts.filterNot {
                            it.id == account.id
                        }
                        vm.updateSettings(settings.copy(accounts = reordered))
                    }
                )
            }

            AddAccountButton(onPick = { creatingFrom = it })

            HorizontalDivider()

            // ===================================================== ROUTING
            SectionHeading("Which account does what")
            Text(
                text = "Point each job at a different account. Anything left alone uses " +
                    "the first account in the list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Capability.entries.forEach { capability ->
                CapabilityRoute(
                    capability = capability,
                    settings = settings,
                    onRoute = { accountId -> vm.updateSettings(settings.withRoute(capability, accountId)) }
                )
            }

            HorizontalDivider()

            // ===================================================== CHAT
            SectionHeading("Chat behaviour")

            Text(
                text = "${stringResource(R.string.settings_temperature)}: " +
                    "%.1f".format(temperature),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = temperature.toFloat(),
                onValueChange = { temperature = it.toDouble() },
                valueRange = 0f..2f,
                steps = 19
            )

            Text(
                text = "Top P: %.2f".format(topP),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = topP.toFloat(),
                onValueChange = { topP = it.toDouble() },
                valueRange = 0.1f..1f,
                steps = 17
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.settings_system_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                maxLines = 12
            )

            HorizontalDivider()

            // ===================================================== LIVE DATA
            SectionHeading("Live information")
            Text(
                text = "Search gives the model today's facts instead of its memory. " +
                    "Automatic searches when a question looks time-sensitive, such as " +
                    "asking for current standings, prices or news.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Web search", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                SearchMode.entries.forEach { mode ->
                    val selected = searchMode == mode
                    if (selected) {
                        Button(onClick = { searchMode = mode }) { Text(mode.label) }
                    } else {
                        OutlinedButton(onClick = { searchMode = mode }) { Text(mode.label) }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download the top pages",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Slower and uses more of the model's memory, but it is what " +
                            "lets tables and figures be answered accurately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = fetchPages, onCheckedChange = { fetchPages = it })
            }

            NumberField(
                label = "Search results to gather",
                value = maxResults,
                onChange = { maxResults = it }
            )
            NumberField(
                label = "Pages to download (0 disables)",
                value = maxPages,
                onChange = { maxPages = it }
            )

            HorizontalDivider()

            // ===================================================== IMAGES
            SectionHeading("Images")
            Text(
                text = "Size requested from the image-generation account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IMAGE_SIZES.take(3).forEach { size ->
                    if (imageSize == size) {
                        Button(onClick = { imageSize = size }) { Text(size) }
                    } else {
                        OutlinedButton(onClick = { imageSize = size }) { Text(size) }
                    }
                }
            }

            HorizontalDivider()

            // ===================================================== APPEARANCE
            SectionHeading("Appearance")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use the device voice for read-aloud",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Free, offline and instant. Turn this off to use the cloud " +
                            "read-aloud account instead, which costs money per request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = useDeviceVoice, onCheckedChange = { useDeviceVoice = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_dark_theme),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = darkTheme, onCheckedChange = { darkTheme = it })
            }

            Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_save))
            }
            if (savedNotice) {
                Text(
                    text = stringResource(R.string.settings_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            // ===================================================== ABOUT
            SectionHeading("About SmartTalk")
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "SmartTalk is commercial, proprietary software. It is licensed, not " +
                    "sold. Reverse engineering, modification, redistribution or resale " +
                    "requires prior written permission from the copyright holder. See the " +
                    "LICENSE.txt file supplied with this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "\u00A9 ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} " +
                    "Akankwatsa Andrew OBA. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ------------------------------------------------------------- dialogs

    editing?.let { account ->
        AccountEditorDialog(
            initial = account,
            isNew = false,
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.updateSettings(settings.withAccountReplaced(updated))
                editing = null
            }
        )
    }

    creatingFrom?.let { presetId ->
        val preset = BUILT_IN_PROVIDERS.firstOrNull { it.id == presetId }
            ?: BUILT_IN_PROVIDERS.last()
        AccountEditorDialog(
            initial = ApiAccount.fromPreset(preset, labelSuffix = " 2"),
            isNew = true,
            onDismiss = { creatingFrom = null },
            onSave = { created ->
                vm.updateSettings(settings.copy(accounts = settings.accounts + created))
                creatingFrom = null
            }
        )
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this account?") },
            text = {
                Text(
                    "\"${account.label}\" and its saved API key will be removed from this " +
                        "device. Conversations are not affected. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(settings.withAccountRemoved(account.id))
                    pendingDelete = null
                }) { Text("Delete account") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            }
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun AccountRow(
    account: ApiAccount,
    isDefaultChat: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (isDefaultChat) {
                    Text(
                        text = "default",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = account.model.ifBlank { "no model set" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    account.apiKey.isBlank() && !account.isLocalEndpoint -> "no API key"
                    account.apiKey.isBlank() -> "local server, no key needed"
                    else -> "key set (${account.apiKey.take(4)}\u2026)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (account.apiKey.isBlank() && !account.isLocalEndpoint) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row {
                TextButton(onClick = onEdit) { Text("Edit") }
                if (!isDefaultChat) {
                    TextButton(onClick = onMakeDefault) { Text("Make default") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun AddAccountButton(onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Add an account") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BUILT_IN_PROVIDERS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        onPick(preset.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CapabilityRoute(
    capability: Capability,
    settings: AppSettings,
    onRoute: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dedicated = settings.accountById(settings.routes[capability.id])
    val effective = settings.accountFor(capability)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(capability.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = capability.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Using: ${effective?.label ?: "nothing configured"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(if (dedicated != null) "Change" else "Set")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Same as chat") },
                        onClick = {
                            onRoute(null)
                            expanded = false
                        }
                    )
                    settings.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.label) },
                            onClick = {
                                onRoute(account.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() }.take(2)) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * Editor for one account. The model field is free text with a suggestion list,
 * because gateways rename models constantly and a fixed list would rot.
 */
@Composable
private fun AccountEditorDialog(
    initial: ApiAccount,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ApiAccount) -> Unit
) {
    var label by remember { mutableStateOf(initial.label) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var showModelHints by remember { mutableStateOf(false) }

    val suggestions = when {
        model.contains("dall", true) || model.contains("flux", true) ||
            model.contains("diffusion", true) || model.contains("sdxl", true) ->
            SUGGESTED_IMAGE_MODELS
        initial.presetId == "custom" -> SUGGESTED_CHAT_MODELS
        else -> SUGGESTED_CHAT_MODELS
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New account" else "Edit account") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide key" else "Show key")
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                TextButton(onClick = { showModelHints = !showModelHints }) {
                    Text(if (showModelHints) "Hide examples" else "Show model examples")
                }
                if (showModelHints) {
                    Text(
                        text = suggestions.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Image models: " + SUGGESTED_IMAGE_MODELS.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Vision models: " + SUGGESTED_VISION_MODELS.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { typed -> maxTokens = typed.filter { it.isDigit() }.take(7) },
                    label = { Text("Max response tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Text(
                    text = "Keys are stored only on this device, in this app's private " +
                        "storage, and are sent only to the provider you name here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.ifBlank { "Account" },
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            maxTokens = maxTokens.toIntOrNull() ?: 8192
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
