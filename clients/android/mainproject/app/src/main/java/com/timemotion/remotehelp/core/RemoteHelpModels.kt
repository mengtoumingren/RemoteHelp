package com.timemotion.remotehelp.core

enum class DeviceSide(val title: String, val subtitle: String) {
    HELPER("我要协助", "发起协助、视频核验、远程操作"),
    ELDER("需要协助", "接收链接、确认身份、授权协助")
}

enum class HelpStage(val title: String, val description: String) {
    DRAFT("待发起", "填写手机号后生成本次协助请求"),
    REQUEST_CREATED("待验证", "短信链接已生成，等待进入视频验证"),
    VERIFYING("视频核验中", "等待协助方确认后建立音视频通话"),
    VERIFIED("已通过验证", "可以进入远程协助阶段"),
    ASSISTING("协助中", "屏幕共享和远程控制进行中"),
    REJECTED("已拒绝", "对方拒绝了本次协助"),
    ENDED("已结束", "本次协助已结束")
}

enum class AppScreen {
    DASHBOARD,
    SESSION,
    VERIFICATION,
    ASSIST
}

data class RecentContact(
    val name: String,
    val phone: String,
    val lastHelpTime: Long,
    val helpCount: Int
)

data class SessionHistoryItem(
    val requestId: String,
    val helperName: String,
    val elderName: String,
    val elderPhone: String,
    val startedAt: Long,
    val endedAt: Long,
    val endReason: String
)

data class ActiveHelpSession(
    val requestId: String,
    val helperName: String,
    val elderName: String,
    val elderPhone: String,
    val createdAt: Long,
    val expiresAt: Long,
    val inviteToken: String,
    val deepLink: String,
    val stage: HelpStage = HelpStage.REQUEST_CREATED,
    val verificationAcceptedAt: Long? = null,
    val endReason: String? = null
) {
    val sessionId: String = requestId
    val sessionRoomId: String = sessionId
    val verificationRoomId: String = sessionRoomId
    val remoteRoomId: String = sessionRoomId

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now > expiresAt
}

data class RemoteHelpUiState(
    val side: DeviceSide = DeviceSide.HELPER,
    val serverUrl: String = "ws://10.0.2.2:3000/ws",
    val helperName: String = "张三",
    val elderName: String = "联系人",
    val elderPhone: String = "13800138000",
    val inviteEntry: String = "",
    val pendingInviteSession: ActiveHelpSession? = null,
    val isVerificationRequestVisible: Boolean = false,
    val activeSession: ActiveHelpSession? = null,
    val recentContacts: List<RecentContact> = emptyList(),
    val history: List<SessionHistoryItem> = emptyList(),
    val currentScreen: AppScreen = AppScreen.DASHBOARD,
    val bannerMessage: String? = null,
    val isSettingsVisible: Boolean = false
)
