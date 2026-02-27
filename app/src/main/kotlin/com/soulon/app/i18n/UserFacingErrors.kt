package com.soulon.app.i18n

object UserFacingErrors {
    fun networkError(detail: String? = null): String {
        val d = detail?.trim().orEmpty()
        return if (d.isBlank()) {
            AppStrings.tr("网络错误，请稍后重试", "Network error, please try again later")
        } else {
            AppStrings.trf("网络错误: %s", "Network error: %s", d)
        }
    }

    fun serverError(status: Int): String {
        return AppStrings.trf("服务器错误: %d", "Server error: %d", status)
    }

    fun pleaseConnectWalletFirst(): String {
        return AppStrings.tr("请先连接钱包", "Please connect your wallet first")
    }

    fun decryptFailed(detail: String? = null): String {
        val d = detail?.trim().orEmpty()
        return if (d.isBlank()) {
            AppStrings.tr("解密失败", "Decryption failed")
        } else {
            AppStrings.trf("解密失败：%s", "Decryption failed: %s", d)
        }
    }

    fun generationInterrupted(): String {
        return AppStrings.tr("生成被中断，请重试。", "Generation interrupted. Please retry.")
    }

    fun genericRetryLater(): String {
        return AppStrings.tr("抱歉，出现错误，请稍后重试。", "Sorry, something went wrong. Please try again later.")
    }
}

