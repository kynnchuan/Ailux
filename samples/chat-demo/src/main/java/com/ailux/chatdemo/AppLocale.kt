package com.ailux.chatdemo

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported app languages for the demo.
 */
enum class AppLanguage(val code: String, val displayName: String) {
    CHINESE("zh", "中文"),
    ENGLISH("en", "English"),
}

/**
 * Global app locale state. Observed by Compose via CompositionLocal.
 * Switching language triggers recomposition of the entire UI tree.
 */
object AppLocaleManager {
    private val _language = MutableStateFlow(AppLanguage.CHINESE)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun switchTo(lang: AppLanguage) {
        _language.value = lang
    }
}

/**
 * CompositionLocal for accessing current language in any composable.
 */
val LocalAppLanguage = compositionLocalOf { AppLanguage.CHINESE }

/**
 * String resource table for the demo app.
 * All user-visible strings are defined here for both languages.
 */
object Strings {
    // ─── Debug Panel ───
    val panelTitle get() = s("Ailux 调试面板", "Ailux Debug Panel")
    val panelSubtitle get() = s("运行时配置 v0.2.2~v0.2.4 功能验证", "Runtime config for v0.2.2~v0.2.4 feature testing")
    val rebuild get() = s("重建", "Rebuild")
    val rebuildClient get() = s("重建客户端", "Rebuild Client")

    // Section headers
    val requestLevel get() = s("请求级参数", "Request-level")
    val requestLevelDesc get() = s("即时生效，无需重建", "Instant, no rebuild needed")
    val clientLevel get() = s("客户端级参数", "Client-level")
    val clientLevelDesc get() = s("修改后需要重建客户端", "Requires client rebuild to take effect")

    // Fields
    val model get() = s("模型", "Model")
    val modelPlaceholder get() = s("如 deepseek-v4-flash, gpt-4o", "e.g. deepseek-v4-flash, gpt-4o")
    val providerRouting get() = s("Provider 路由 (overrides.provider)", "Provider (overrides.provider)")
    val providerRoutingPlaceholder get() = s("如 deepseek, openai", "e.g. deepseek, openai")
    val contextMode get() = s("上下文模式", "Context mode")
    val account get() = s("账号", "Account")
    val sessionId get() = s("会话 ID", "Session ID")
    val newSession get() = s("新建", "New")
    val stopSequences get() = s("停止序列（逗号分隔）", "Stop sequences (comma-separated)")
    val stopPlaceholder get() = s("如 \\n\\n,END", "e.g. \\n\\n,END")
    val customOverrides get() = s("自定义 overrides JSON (v0.2.4)", "Custom overrides JSON (v0.2.4)")
    val attachTestImage get() = s("附加测试图片 (v0.2.4 多模态)", "Attach test image (v0.2.4 multimodal)")
    val providerMode get() = s("Provider 模式", "Provider mode")
    val stallDetection get() = s("停滞检测 (v0.2.3)", "Stall detection (v0.2.3)")
    val stallThreshold get() = s("停滞空闲阈值 (ms)", "Stall idle threshold (ms)")
    val concurrencyPolicy get() = s("并发策略 (v0.2.3)", "Concurrency policy (v0.2.3)")
    val language get() = s("应用语言", "App language")

    // Provider mode labels
    val mockProvider get() = s("MockProvider", "MockProvider")
    val mockProviderDesc get() = s("离线模式", "Offline Mode")
    val backendProxy get() = s("BackendProxy", "BackendProxy")
    val backendProxyDesc get() = s("后端代理模式", "Backend proxy mode")
    val localRuntime get() = s("LocalRuntime", "LocalRuntime")
    val localRuntimeDesc get() = s("端侧私密 AI · 首次需下载模型", "On-device private AI · First use requires model download")

    // Account labels
    val freeTier get() = s("免费版 (5次/分)", "Free tier (5 req/min)")
    val proTier get() = s("专业版 (100次/分)", "Pro tier (100 req/min)")
    val adminTier get() = s("管理员 (无限制)", "Admin (unlimited)")

    // ─── Diagnostics (B2-2, DEBUG-only) ───
    val diagnostics get() = s("诊断 (B2-2)", "Diagnostics (B2-2)")
    val diagnosticsDesc get() = s(
        "复制 toShareableText() 输出到剪贴板。已脱敏，可贴 GitHub Issue。",
        "Copy toShareableText() output to clipboard. Redacted; safe for GitHub Issues."
    )
    val copyLastTaskDiagnostic get() = s("复制最近一次任务诊断", "Copy last-task diagnostic")
    val copySessionDiagnostic get() = s("复制会话诊断（最近 5 个任务）", "Copy session diagnostic (last 5 tasks)")
    val privacyVerbose get() = s("Privacy verbose (DEBUG_VERBOSE)", "Privacy verbose (DEBUG_VERBOSE)")
    val privacyVerboseDesc get() = s(
        "开启后下次重建客户端将记录 prompt/response/overrides + HTTP headers（凭据头仍永久脱敏）。",
        "When on, the next client rebuild logs prompt/response/overrides + HTTP headers (credential headers still permanently redacted)."
    )
    val toastNoDiagnostic get() = s("还没有任务可用于诊断", "No task available for diagnostics yet")
    val toastDiagnosticCopied get() = s("诊断已复制到剪贴板", "Diagnostic copied to clipboard")

    // ─── Chat Screen ───
    val appTitle get() = s("Ailux Chat", "Ailux Chat")
    val connecting get() = s("连接中...", "Connecting...")
    val generating get() = s("生成中", "Generating")
    val tokens get() = s("token", "tokens")
    val cancelling get() = s("取消中...", "Cancelling...")
    val typeMessage get() = s("输入消息...", "Type a message...")
    val send get() = s("发送", "Send")
    val cancel get() = s("取消", "Cancel")

    // ─── Input Bar Chips ───
    val chipMock get() = s("Mock 模式", "Mock")
    val chipBackend get() = s("后端代理", "Backend")
    val chipLocal get() = s("本地模型", "On-device")
    val chipSelectModel get() = s("选择模型", "Select model")
    val sheetSelectProvider get() = s("选择运行模式", "Select provider mode")

    // ─── Drawer ───
    val drawerConversations get() = s("对话", "CONVERSATIONS")
    val drawerNoConversations get() = s("暂无对话，点击 + 开始新对话", "No conversations yet. Tap + to start.")
    val drawerModels get() = s("模型管理", "MODELS")
    val drawerNoModel get() = s("未下载模型", "No model downloaded")
    val drawerModelReady get() = s("已就绪", "Ready")
    val drawerSettings get() = s("设置", "SETTINGS")
    val drawerSettingsSystemPrompt get() = s("系统提示词", "System prompt")
    val drawerSettingsSampling get() = s("采样参数", "Sampling parameters")
    val drawerSettingsContext get() = s("上下文策略", "Context strategy")
    val drawerDevTools get() = s("开发者工具", "DEVELOPER TOOLS")
    val drawerDevToolsProvider get() = s("Provider 配置", "Provider config")
    val drawerDevToolsDiagnostics get() = s("诊断信息", "Diagnostics")
    val drawerDevToolsPrivacy get() = s("隐私与日志", "Privacy & logging")
    val newChat get() = s("新对话", "New chat")

    // ─── Delete Confirmation ───
    val deleteConfirmTitle get() = s("删除对话", "Delete conversation")
    val deleteConfirmMessage get() = s("确定要删除这个对话吗？删除后无法恢复。", "Are you sure you want to delete this conversation? This cannot be undone.")
    val deleteConfirmYes get() = s("删除", "Delete")
    val deleteConfirmNo get() = s("取消", "Cancel")

    // ─── Attachment Panel ───
    val attachmentPanelTitle get() = s("更多", "More")
    val pickFromGallery get() = s("从相册选择图片", "Pick from gallery")
    val pickFromGalleryDesc get() = s("选择图片发送给 AI 进行分析", "Select an image to send to AI for analysis")

    // ─── Quick Prompts ───
    val quickPrompt1 get() = s("帮我写一段代码", "Write some code for me")
    val quickPrompt2 get() = s("解释一个概念", "Explain a concept")
    val quickPrompt3 get() = s("帮我翻译", "Help me translate")
    val quickPrompt4 get() = s("今天天气怎么样", "What's the weather today")

    // ─── Image Attachment ───
    val imageAttached get() = s("已选择图片", "Image attached")

    // ─── Model Management ───
    val drawerDownloadModels get() = s("下载更多模型", "Download more models")

    private fun s(zh: String, en: String): String =
        if (AppLocaleManager.language.value == AppLanguage.CHINESE) zh else en
}
