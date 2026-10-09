package com.smarttalk.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: ChatViewModel, onDone: () -> Unit) {
    val settings = vm.settings

    var providerId by remember { mutableStateOf(settings.providerId) }
    var baseUrl by remember { mutableStateOf(settings.activeProvider().baseUrl) }
    var apiKey by remember { mutableStateOf(settings.activeProvider().apiKey) }
    var model by remember { mutableStateOf(settings.activeProvider().model) }
    var maxTokens by remember { mutableStateOf(settings.activeProvider().maxTokens.toString()) }
    var temperature by remember { mutableStateOf(settings.temperature) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var webSearch by remember { mutableStateOf(settings.webSearchEnabled) }
    var darkTheme by remember { mutableStateOf(settings.darkTheme) }
    var showKey by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    val current = BUILT_IN_PROVIDERS.firstOrNull { it.id == providerId } ?: BUILT_IN_PROVIDERS[0]

    fun chooseProvider(id: String) {
        val cfg = settings.copy(providerId = id).activeProvider()
        providerId = id
        baseUrl = cfg.baseUrl
        apiKey = cfg.apiKey
        model = cfg.model
        maxTokens = cfg.maxTokens.toString()
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
            // -------------------------------------------------------- provider
            Text(
                text = stringResource(R.string.settings_provider),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(current.label) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BUILT_IN_PROVIDERS.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = { chooseProvider(p.id); expanded = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text(stringResource(R.string.settings_base_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.settings_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                }
            )

            Text(
                text = stringResource(R.string.settings_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.settings_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = maxTokens,
                onValueChange = { typed -> maxTokens = typed.filter { it.isDigit() }.take(7) },
                label = { Text(stringResource(R.string.settings_max_tokens)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // ----------------------------------------------------- temperature
            Column {
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
            }

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text(stringResource(R.string.settings_system_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                maxLines = 12
            )

            // --------------------------------------------------------- toggles
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_web_search),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = webSearch, onCheckedChange = { webSearch = it })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_dark_theme),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = darkTheme, onCheckedChange = { darkTheme = it })
            }

            Button(
                onClick = {
                    val override = ProviderOverride(
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        maxTokens = maxTokens.toIntOrNull() ?: current.maxTokens
                    )
                    vm.updateSettings(
                        settings.copy(
                            providerId = providerId,
                            temperature = temperature,
                            systemPrompt = systemPrompt,
                            webSearchEnabled = webSearch,
                            darkTheme = darkTheme
                        ).withOverride(providerId, override)
                    )
                    savedNotice = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_save)) }

            if (savedNotice) {
                Text(
                    text = stringResource(R.string.settings_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            // ----------------------------------------------------------- about
            Text(
                text = "About SmartTalk",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} \u00B7 Android ${android.os.Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "SmartTalk is commercial, proprietary software. It is licensed, not sold. " +
                    "Reverse engineering, modification, redistribution or resale requires prior " +
                    "written permission from the copyright holder. See the LICENSE.txt file " +
                    "supplied with this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "\u00A9 ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} " +
                    "Akankwatsa Andrew OBA. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
