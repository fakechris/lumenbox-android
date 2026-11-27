package com.lumenopen.lumenbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenopen.lumenbox.push.ConnectionPushPreferences
import com.lumenopen.lumenbox.push.DevicePushSyncCoordinator
import com.lumenopen.lumenbox.push.PushNotificationLevel
import com.lumenopen.lumenbox.storage.InstallationStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PushPreferencesSectionCard(
    connectionId: String,
    isOnline: Boolean,
    store: InstallationStore,
    pushSyncCoordinator: DevicePushSyncCoordinator?,
) {
    if (pushSyncCoordinator == null) return
    val scope = rememberCoroutineScope()
    var prefs by remember(connectionId) {
        mutableStateOf(pushSyncCoordinator.readPreferences(connectionId))
    }
    var redactLockScreen by remember(connectionId) {
        mutableStateOf(store.findById(connectionId)?.redactLockScreenNotifications ?: false)
    }
    var syncNotice by remember(connectionId) { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("零明文唤醒推送 (Wake-Only Push)", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅通过网关转发 { connId, seq } 唤醒信封；本机收到后先秒弹通用兜底通知，再直连服务器拉取真实标题并支持锁屏脱敏。",
                style = MaterialTheme.typography.bodySmall,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val options = listOf(
                    PushNotificationLevel.NEEDS_YOU_AND_COMPLETIONS to "待决 + 任务完成",
                    PushNotificationLevel.NEEDS_YOU_ONLY to "仅 Needs you 待决",
                    PushNotificationLevel.OFF to "关闭推送",
                )
                for ((lvl, label) in options) {
                    FilterChip(
                        selected = prefs.level == lvl,
                        onClick = {
                            val updated = prefs.copy(level = lvl)
                            prefs = updated
                            pushSyncCoordinator.savePreferencesLocal(connectionId, updated)
                            if (isOnline) {
                                scope.launch {
                                    val synced = runCatching {
                                        pushSyncCoordinator.syncConnectionPush(connectionId, updated)
                                    }.getOrDefault(false)
                                    syncNotice = if (synced) {
                                        "已同步至 /api/v1/devices/self/push"
                                    } else {
                                        "偏好已本地保存（待原生推送令牌就绪后自动上报）"
                                    }
                                }
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("锁屏通知脱敏", style = MaterialTheme.typography.bodyMedium)
                    Text("锁屏仅显示连接名称与通用提示，隐藏具体审批与任务标题", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = redactLockScreen,
                    onCheckedChange = { checked ->
                        redactLockScreen = checked
                        store.updateLockScreenRedaction(connectionId, checked)
                    },
                )
            }

            if (isOnline) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = runCatching { pushSyncCoordinator.syncConnectionPush(connectionId, prefs) }
                                .getOrDefault(false)
                            syncNotice = if (ok) {
                                "已成功同步 LumenPushToken 能力令牌与偏好"
                            } else {
                                "当前环境未返回原生推送码，钉钉/飞书卡片 lumenbox://open/... 深链仍可直接唤起"
                            }
                        }
                    },
                ) {
                    Text("立即同步推送配置")
                }
            }

            syncNotice?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
