package com.lumenopen.lumenbox.app.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumenopen.lumenbox.session.OrganisationOnboardingState
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import kotlinx.coroutines.launch

/**
 * Organisation server discovery + AndroidX Custom Tabs OAuth 2.0 + PKCE (`S256`) sign-in screen
 * (`docs/106 §4.2–4.3`, `docs/109`, `docs/110 §4`, INV-2255).
 */
@Composable
fun OrganisationSignInScreen(
    state: OrganisationOnboardingState,
    coordinator: OrganisationSessionCoordinator,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrlInput by remember { mutableStateOf("") }
    var launchError by remember { mutableStateOf<String?>(null) }

    val isBusy = state is OrganisationOnboardingState.Discovering ||
        state is OrganisationOnboardingState.ExchangingCode

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("连接企业组织服务器", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                onClick = {
                    coordinator.resetOnboarding()
                    onClose()
                },
                enabled = !isBusy,
            ) {
                Text("返回")
            }
        }

        Text(
            "输入组织 LumenBox 服务器 HTTPS 地址（支持系统级企业自签 / MDM 根证书），使用钉钉或飞书单点登录获取硬件级轮换设备凭据。",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = {
                serverUrlInput = it
                launchError = null
            },
            label = { Text("组织服务器地址") },
            placeholder = { Text("https://lumenbox.company.example") },
            singleLine = true,
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                launchError = null
                scope.launch {
                    runCatching { coordinator.discoverProviders(serverUrlInput) }
                }
            },
            enabled = !isBusy && serverUrlInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("识别企业登录渠道 (/api/v1/meta)")
        }

        val errorText = launchError ?: (state as? OrganisationOnboardingState.Failed)?.errorMessage
        if (!errorText.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        when (state) {
            OrganisationOnboardingState.Idle -> Unit

            is OrganisationOnboardingState.Discovering -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在读取 ${state.serverUrl} 的 /api/v1/meta 与登录渠道…")
                }
            }

            is OrganisationOnboardingState.ProvidersReady -> {
                val orgName = state.discovery.meta.installation.name ?: state.discovery.normalizedOrigin
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("已识别组织：$orgName", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请选择单点登录方式（OAuth 2.0 + PKCE S256）：",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        for (provider in state.discovery.providers) {
                            Button(
                                onClick = {
                                    launchError = null
                                    scope.launch {
                                        runCatching {
                                            val authUrl = coordinator.beginCustomTabsSignIn(
                                                discovery = state.discovery,
                                                providerId = provider.id.rawValue,
                                            )
                                            val customTabs = CustomTabsIntent.Builder()
                                                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                                                .build()
                                            customTabs.launchUrl(context, Uri.parse(authUrl))
                                        }.onFailure { err ->
                                            launchError = "无法打开系统安全浏览器标签页：${err.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("使用 ${provider.label} 登录")
                            }
                        }
                    }
                }
            }

            is OrganisationOnboardingState.AwaitingBrowserCallback -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "正在等待 ${state.pending.organisationDisplayName} 授权回调…",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "请在弹出的 Custom Tabs 页面中完成钉钉/飞书认证，完成后将自动跳回 LumenBox。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    CustomTabsIntent.Builder()
                                        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                                        .build()
                                        .launchUrl(context, Uri.parse(state.pending.authorizationUrl))
                                }.onFailure { err ->
                                    launchError = "重新打开浏览器失败：${err.message}"
                                }
                            },
                        ) {
                            Text("重新打开授权页")
                        }
                    }
                }
            }

            is OrganisationOnboardingState.ExchangingCode -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在向 ${state.organisationName} 兑换设备凭据并登记硬件步进签名公钥…")
                }
            }

            is OrganisationOnboardingState.AskedToJoin -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "已提交加入申请（待管理员激活）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "组织：${state.organisationName}（${state.serverOrigin}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        state.requestId?.let { reqId ->
                            Text(
                                "申请编号：$reqId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                serverUrlInput = state.serverOrigin
                                scope.launch {
                                    runCatching { coordinator.discoverProviders(state.serverOrigin) }
                                }
                            },
                        ) {
                            Text("管理员已激活？重新检查并登录")
                        }
                    }
                }
            }

            is OrganisationOnboardingState.Failed -> Unit
        }
    }
}
