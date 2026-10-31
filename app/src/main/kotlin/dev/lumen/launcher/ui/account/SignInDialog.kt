package dev.lumen.launcher.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.launcher.AppState
import dev.lumen.launcher.auth.AuthException
import dev.lumen.launcher.auth.DeviceCode
import dev.lumen.launcher.ui.tr
import java.awt.Desktop
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

private sealed interface SignInUi {
    data object Requesting : SignInUi
    data class Code(val code: DeviceCode) : SignInUi
    data class Working(val stage: String) : SignInUi
    data class Failed(val message: String, val retryable: Boolean) : SignInUi
}

/**
 * Device-code sign-in: the player completes sign-in on Microsoft's own page in their browser.
 * Closing the dialog cancels the coroutine, which stops polling Microsoft.
 */
@Composable
fun SignInDialog(state: AppState, onDismiss: () -> Unit) {
    var ui by remember { mutableStateOf<SignInUi>(SignInUi.Requesting) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        ui = SignInUi.Requesting
        try {
            val account = state.accounts.signIn(onCode = { ui = SignInUi.Code(it) }, onStage = { ui = SignInUi.Working(it) })
            state.status = state.t("Signed in as ${account.name}", "已登录为 ${account.name}")
            onDismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Configuration problems won't fix themselves on retry.
            val retryable = e !is AuthException.NotConfigured && e !is AuthException.AppNotFound &&
                e !is AuthException.PublicClientDisabled && e !is AuthException.AppNotApproved
            ui = SignInUi.Failed(e.message ?: state.t("Sign-in failed", "登录失败"), retryable)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Sign in with Microsoft", "使用微软账号登录")) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (val s = ui) {
                    SignInUi.Requesting -> Busy(tr("Contacting Microsoft…", "正在连接微软…"))
                    is SignInUi.Code -> CodeSteps(s.code)
                    is SignInUi.Working -> Busy(s.stage + "…")
                    is SignInUi.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    val keychain = state.accounts.keychain
                    Text(
                        tr("Lumen never sees your password. ", "Lumen 不会接触你的密码。") + if (keychain.isAvailable) {
                            tr(
                                "Only a sign-in refresh token is kept, in ${keychain.description}.",
                                "仅会在 ${keychain.description} 中保存一个登录刷新令牌。",
                            )
                        } else {
                            tr(
                                "No system keychain was found, so you'll need to sign in again next time Lumen starts.",
                                "未找到系统钥匙串，下次启动 Lumen 时需要重新登录。",
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = ui) {
                is SignInUi.Code -> Button(onClick = { openTrusted(s.code.verificationUri) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Open sign-in page", "打开登录页面"))
                }
                is SignInUi.Failed -> if (s.retryable) Button(onClick = { attempt++ }) { Text(tr("Try again", "重试")) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "取消")) } },
    )
}

@Composable
private fun CodeSteps(code: DeviceCode) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code.userCode) { mutableStateOf(false) }
    val host = runCatching { URI(code.verificationUri).host.removePrefix("www.") + URI(code.verificationUri).path }.getOrDefault(code.verificationUri)

    Text(tr("1. Open $host in your browser", "1. 在浏览器中打开 $host"), style = MaterialTheme.typography.bodyMedium)
    Text(tr("2. Enter this code:", "2. 输入以下代码："), style = MaterialTheme.typography.bodyMedium)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                code.userCode,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = 4.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code.userCode)); copied = true }) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, if (copied) tr("Copied", "已复制") else tr("Copy code", "复制代码"))
            }
        }
    }
    Busy(tr("Waiting for you to finish in the browser…", "等待你在浏览器中完成登录…"))
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only ever opens Microsoft's own sign-in hosts, whatever the server response says. */
private fun openTrusted(uri: String) {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return
    val host = parsed.host?.lowercase() ?: return
    val trusted = parsed.scheme == "https" &&
        (host == "microsoft.com" || host.endsWith(".microsoft.com") || host.endsWith(".live.com") || host.endsWith(".microsoftonline.com"))
    if (trusted && Desktop.isDesktopSupported()) {
        Thread { runCatching { Desktop.getDesktop().browse(parsed) } }.start()
    }
}
