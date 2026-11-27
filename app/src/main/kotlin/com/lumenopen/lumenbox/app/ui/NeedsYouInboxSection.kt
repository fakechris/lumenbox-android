package com.lumenopen.lumenbox.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApprovalGrantScope
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.inbox.NeedsYouInboxCoordinator
import com.lumenopen.lumenbox.inbox.ToolApprovalDecision
import com.lumenopen.lumenbox.inbox.WorkResolutionKind
import com.lumenopen.lumenbox.security.StepUpCancelledException
import kotlinx.coroutines.launch

/**
 * Polymorphic `Needs you` Compose inbox section (`docs/106 §4.2`, `docs/110 §1, §4.4`, INV-2256).
 *
 * - Tool approvals expose ONLY `once` and `session` (never `"always"`), invoking hardware
 *   `BiometricPrompt` when `stepUpRequired == true`.
 * - `SecretPrompt` (`AskSecret`) opens a `FLAG_SECURE` screenshot/screen-recording-protected
 *   write-only dialog that submits straight into the server vault without local caching.
 * - Unknown decision kinds (`Other`) render `summary` gracefully and guide the user to desktop/web.
 */
@Composable
fun NeedsYouInboxSection(
    items: List<InboxDecisionItem>,
    isOnline: Boolean,
    pendingOfflineActions: Int = 0,
    coordinator: NeedsYouInboxCoordinator,
    onItemsChanged: (List<InboxDecisionItem>) -> Unit,
    onAccessRemoved: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var activeSecretPrompt by remember { mutableStateOf<InboxDecisionItem.SecretPrompt?>(null) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var busyItemId by remember { mutableStateOf<String?>(null) }

    fun executeDecision(itemId: String, block: suspend () -> Unit) {
        busyItemId = itemId
        actionFeedback = null
        scope.launch {
            try {
                block()
                onItemsChanged(coordinator.readCachedItems())
            } catch (_: StepUpCancelledException) {
                actionFeedback = "已取消生物识别确认，审批未发送。"
            } catch (err: ApiEnvelopeException) {
                if (err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                } else {
                    actionFeedback = "提交失败（${err.envelope.code}）：${err.envelope.error}"
                }
            } catch (err: Throwable) {
                actionFeedback = err.message ?: "操作执行失败"
            } finally {
                busyItemId = null
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Needs you 待决收件箱（${items.size}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (isOnline) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    coordinator.flushPendingDecisions()
                                    onItemsChanged(coordinator.refreshInbox())
                                }.onFailure { err ->
                                    if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                                        onAccessRemoved?.invoke()
                                    } else {
                                        actionFeedback = err.message
                                    }
                                }
                            }
                        },
                    ) {
                        Text("同步")
                    }
                }
            }

            if (pendingOfflineActions > 0) {
                Text(
                    "离线待重放幂等决策队列：$pendingOfflineActions 项",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            actionFeedback?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (items.isEmpty()) {
                Text("当前没有等待你处理的决策。", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (item in items) {
                    HorizontalDivider()
                    val isItemBusy = busyItemId == item.id
                    when (item) {
                        is InboxDecisionItem.ToolApproval -> ToolApprovalCard(
                            item = item,
                            busy = isItemBusy,
                            onDecision = { decision ->
                                executeDecision(item.id) {
                                    coordinator.submitToolApproval(item, decision)
                                }
                            },
                        )

                        is InboxDecisionItem.SecretPrompt -> SecretPromptCard(
                            item = item,
                            busy = isItemBusy,
                            isOnline = isOnline,
                            onOpenSecureSheet = { activeSecretPrompt = item },
                        )

                        is InboxDecisionItem.OutboxDraft -> OutboxDraftCard(
                            item = item,
                            busy = isItemBusy,
                            onApprove = { approve ->
                                executeDecision(item.id) {
                                    coordinator.decideOutboxDraft(item, approve)
                                }
                            },
                        )

                        is InboxDecisionItem.Question -> QuestionCard(
                            item = item,
                            busy = isItemBusy,
                            onSubmitAnswer = { answer ->
                                executeDecision(item.id) {
                                    coordinator.answerQuestion(item, answer)
                                }
                            },
                        )

                        is InboxDecisionItem.CloseProposal -> CloseProposalCard(
                            item = item,
                            busy = isItemBusy,
                            onResolve = { kind ->
                                executeDecision(item.id) {
                                    coordinator.resolveWorkItem(item.id, item.workId, kind)
                                }
                            },
                        )

                        is InboxDecisionItem.ExceptionRecovery -> ExceptionRecoveryCard(
                            item = item,
                            busy = isItemBusy,
                            onResolve = { kind ->
                                executeDecision(item.id) {
                                    coordinator.resolveWorkItem(item.id, item.workId, kind)
                                }
                            },
                        )

                        is InboxDecisionItem.Other -> UnknownOtherCard(item = item)
                    }
                }
            }
        }
    }

    activeSecretPrompt?.let { prompt ->
        AskSecretSecureDialog(
            item = prompt,
            busy = busyItemId == prompt.id,
            onDismiss = { activeSecretPrompt = null },
            onSubmitChars = { chars ->
                executeDecision(prompt.id) {
                    coordinator.submitSecretDirectToVault(prompt, chars)
                    activeSecretPrompt = null
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolApprovalCard(
    item: InboxDecisionItem.ToolApproval,
    busy: Boolean,
    onDecision: (ToolApprovalDecision) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工具审批 · ${item.toolName}", style = MaterialTheme.typography.titleSmall)
            if (item.stepUpRequired) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("需生物识别步进签名") },
                )
            }
        }
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        item.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        // Strictly once / session / deny — NO "always" option exists on mobile credentials!
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { onDecision(ToolApprovalDecision.Allow(ApprovalGrantScope.ONCE)) },
                enabled = !busy,
            ) {
                Text(if (item.stepUpRequired) "指纹/面容验证 · 允许一次" else "允许一次 (once)")
            }
            OutlinedButton(
                onClick = { onDecision(ToolApprovalDecision.Allow(ApprovalGrantScope.SESSION)) },
                enabled = !busy,
            ) {
                Text("本次会话允许 (session)")
            }
            OutlinedButton(
                onClick = { onDecision(ToolApprovalDecision.Deny()) },
                enabled = !busy,
            ) {
                Text("拒绝")
            }
        }
    }
}

@Composable
private fun SecretPromptCard(
    item: InboxDecisionItem.SecretPrompt,
    busy: Boolean,
    isOnline: Boolean,
    onOpenSecureSheet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("保险库密钥请求 · ${item.secretName}", style = MaterialTheme.typography.titleSmall)
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        item.hint?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onOpenSecureSheet,
            enabled = !busy && isOnline,
        ) {
            Text(if (isOnline) "在防截屏安全面板中输入密钥" else "离线时不可缓存密钥（请恢复连接）")
        }
    }
}

@Composable
private fun OutboxDraftCard(
    item: InboxDecisionItem.OutboxDraft,
    busy: Boolean,
    onApprove: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("外发草稿确认 · ${item.channel}", style = MaterialTheme.typography.titleSmall)
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        item.draftBody?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onApprove(true) }, enabled = !busy) {
                Text("批准发送")
            }
            OutlinedButton(onClick = { onApprove(false) }, enabled = !busy) {
                Text("丢弃草稿")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    item: InboxDecisionItem.Question,
    busy: Boolean,
    onSubmitAnswer: (String) -> Unit,
) {
    var freeText by remember(item.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("问题确认", style = MaterialTheme.typography.titleSmall)
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        if (item.options.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (option in item.options) {
                    OutlinedButton(
                        onClick = { onSubmitAnswer(option) },
                        enabled = !busy,
                    ) {
                        Text(option)
                    }
                }
            }
        }
        if (item.allowFreeText) {
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                placeholder = { Text("输入回复…") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSubmitAnswer(freeText) },
                enabled = !busy && freeText.isNotBlank(),
            ) {
                Text("提交回答")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloseProposalCard(
    item: InboxDecisionItem.CloseProposal,
    busy: Boolean,
    onResolve: (WorkResolutionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("结项确认", style = MaterialTheme.typography.titleSmall)
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onResolve(WorkResolutionKind.COUNT_AS_DONE) }, enabled = !busy) {
                Text("确认完成")
            }
            OutlinedButton(onClick = { onResolve(WorkResolutionKind.PUT_OFF) }, enabled = !busy) {
                Text("推迟")
            }
            OutlinedButton(onClick = { onResolve(WorkResolutionKind.STOP) }, enabled = !busy) {
                Text("停止任务")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExceptionRecoveryCard(
    item: InboxDecisionItem.ExceptionRecovery,
    busy: Boolean,
    onResolve: (WorkResolutionKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val codeSuffix = item.errorCode?.let { " ($it)" }.orEmpty()
        Text("异常 / 暂停待恢复$codeSuffix", style = MaterialTheme.typography.titleSmall)
        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onResolve(WorkResolutionKind.RETRY) }, enabled = !busy) {
                Text("重试继续")
            }
            OutlinedButton(onClick = { onResolve(WorkResolutionKind.PUT_OFF) }, enabled = !busy) {
                Text("推迟")
            }
            OutlinedButton(onClick = { onResolve(WorkResolutionKind.STOP) }, enabled = !busy) {
                Text("停止")
            }
        }
    }
}

@Composable
private fun UnknownOtherCard(item: InboxDecisionItem.Other) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("待决项（类型：${item.rawKind}）", style = MaterialTheme.typography.titleSmall)
            Text(item.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "当前手机端版本仅展示摘要，请前往桌面端或网页端完成该操作。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Write-only `AskSecret` dialog protected with `WindowManager.LayoutParams.FLAG_SECURE`
 * (`docs/106 §4.2`, `docs/110 §4.4`, INV-2256) so screenshots, screen recordings, and Recents
 * thumbnails never capture the secret value.
 */
@Composable
private fun AskSecretSecureDialog(
    item: InboxDecisionItem.SecretPrompt,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmitChars: (CharArray) -> Unit,
) {
    val context = LocalContext.current
    var secretInput by remember(item.id) { mutableStateOf("") }

    // Apply FLAG_SECURE to the host Activity window for the entire lifetime of the dialog
    DisposableEffect(context) {
        val activityWindow = context.findActivity()?.window
        val hadSecureBefore = activityWindow != null &&
            (activityWindow.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secretInput = ""
            if (!hadSecureBefore) {
                activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!busy) {
                secretInput = ""
                onDismiss()
            }
        },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
    ) {
        // Also apply FLAG_SECURE directly on the Dialog's own window surface!
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("只写保险库密钥 · ${item.secretName}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已启用 FLAG_SECURE 防截屏/防录屏保护。密钥将直接加密写入服务器保险库，绝不写入手机本地缓存或对话记录。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(item.summary, style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text(item.secretName) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            secretInput = ""
                            onDismiss()
                        },
                        enabled = !busy,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val chars = secretInput.toCharArray()
                            secretInput = ""
                            onSubmitChars(chars)
                        },
                        enabled = !busy && secretInput.isNotEmpty(),
                    ) {
                        Text("直投保险库")
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
