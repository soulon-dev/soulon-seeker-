package com.soulon.app.i18n

import androidx.compose.runtime.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用字符串管理器
 * 
 * 提供所有 UI 文本的多语言支持
 */
object AppStrings {
    
    // 当前语言代码
    private var currentLanguageCode: String by mutableStateOf("en")
    
    /**
     * 设置当前语言
     */
    fun setLanguage(languageCode: String) {
        currentLanguageCode = languageCode
    }
    
    /**
     * 获取当前语言代码
     */
    fun getCurrentLanguage(): String = currentLanguageCode

    fun resolve(key: String, languageCode: String = currentLanguageCode): String {
        val lang = languageCode.ifBlank { "en" }
        val baseLang = lang.substringBefore('-')
        return translations[key]?.get(lang)
            ?: translations[key]?.get(baseLang)
            ?: translations[key]?.get("en")
            ?: key
    }

    fun tr(zh: String, en: String): String {
        val baseLang = currentLanguageCode.substringBefore('-')
        if (baseLang == "zh") return zh
        if (baseLang == "en") return en
        val key = autoKey(en)
        val dynamicKey = "$baseLang:$key"
        val dynamic = dynamicTranslations[dynamicKey]
        if (!dynamic.isNullOrBlank()) return dynamic

        val bundled = TranslationBundleStore.get(currentLanguageCode, key)
        if (!bundled.isNullOrBlank()) return bundled

        val static = autoTranslations[key]?.get(baseLang)
            ?: autoTranslations[key]?.get("en")

        if (static.isNullOrBlank()) {
            OnDeviceTranslationManager.translateAsync(
                sourceEnText = en,
                targetLang = currentLanguageCode,
                cacheKey = key
            ) { translated ->
                if (translated.isNotBlank()) {
                    dynamicTranslations[dynamicKey] = translated
                }
            }
        }

        return static ?: en
    }

    fun trf(zhFormat: String, enFormat: String, vararg args: Any?): String {
        val format = tr(zhFormat, enFormat)
        return String.format(format, *args)
    }

    private fun autoKey(text: String): String {
        return autoKeyCache.getOrPut(text) {
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { b -> "%02x".format(b) }
        }
    }

    private val autoKeyCache = ConcurrentHashMap<String, String>()
    private val dynamicTranslations = mutableStateMapOf<String, String>()
    
    // ==================== 通用 ====================
    
    val appName: String get() = get("app_name")
    val confirm: String get() = get("confirm")
    val cancel: String get() = get("cancel")
    val back: String get() = get("back")
    val retry: String get() = get("retry")
    val loading: String get() = get("loading")
    val save: String get() = get("save")
    val delete: String get() = get("delete")
    val edit: String get() = get("edit")
    val done: String get() = get("done")
    val next: String get() = get("next")
    val skip: String get() = get("skip")
    val close: String get() = get("close")
    val search: String get() = get("search")
    val settings: String get() = get("settings")
    val about: String get() = get("about")
    val help: String get() = get("help")
    val continueText: String get() = get("continue")
    
    // ==================== 钱包连接 ====================
    
    val welcomeTitle: String get() = get("welcome_title")
    val welcomeSubtitle: String get() = get("welcome_subtitle")
    val connectWallet: String get() = get("connect_wallet")
    val connecting: String get() = get("connecting")
    val retryConnect: String get() = get("retry_connect")
    val walletSupport: String get() = get("wallet_support")
    
    val featureEncryption: String get() = get("feature_encryption")
    val featureEncryptionDesc: String get() = get("feature_encryption_desc")
    val featureStorage: String get() = get("feature_storage")
    val featureStorageDesc: String get() = get("feature_storage_desc")
    val featureDecentralized: String get() = get("feature_decentralized")
    val featureDecentralizedDesc: String get() = get("feature_decentralized_desc")
    val featureOwnership: String get() = get("feature_ownership")
    val featureOwnershipDesc: String get() = get("feature_ownership_desc")
    val supportedWallets: String get() = get("supported_wallets")
    val languageWelcomeTitle: String get() = get("language_welcome_title")
    val languageWelcomeSubtitle: String get() = get("language_welcome_subtitle")
    
    // ==================== 导航 ====================
    
    val navDashboard: String get() = get("nav_dashboard")
    val navChat: String get() = get("nav_chat")
    val navProfile: String get() = get("nav_profile")
    
    // ==================== 我的页面 ====================
    
    val profileSync: String get() = get("profile_sync")
    val profileSyncDesc: String get() = get("profile_sync_desc")
    val profileLanguage: String get() = get("profile_language")
    val profileAbout: String get() = get("profile_about")
    val profileAboutDesc: String get() = get("profile_about_desc")
    val profileFaq: String get() = get("profile_faq")
    val profileFaqDesc: String get() = get("profile_faq_desc")
    val profileSettings: String get() = get("profile_settings")
    val profileSettingsDesc: String get() = get("profile_settings_desc")
    val profileSectionPreferences: String get() = get("profile_section_preferences")
    val profileNotifications: String get() = get("profile_notifications")
    val profileNotificationsDesc: String get() = get("profile_notifications_desc")
    val profileSecurity: String get() = get("profile_security")
    val profileSecurityDesc: String get() = get("profile_security_desc")
    val profileSectionHelpSupport: String get() = get("profile_section_help_support")
    val profileBugReport: String get() = get("profile_bug_report")
    val profileBugReportDesc: String get() = get("profile_bug_report_desc")
    val profileContactUs: String get() = get("profile_contact_us")
    val profileContactUsDesc: String get() = get("profile_contact_us_desc")

    // ==================== 通知设置 ====================

    val notificationsTitle: String get() = get("notifications_title")
    val notificationsPush: String get() = get("notifications_push")
    val notificationsPushDesc: String get() = get("notifications_push_desc")
    val notificationsMessageTypes: String get() = get("notifications_message_types")
    val notificationsAdventure: String get() = get("notifications_adventure")
    val notificationsAdventureDesc: String get() = get("notifications_adventure_desc")
    val notificationsAiChat: String get() = get("notifications_ai_chat")
    val notificationsAiChatDesc: String get() = get("notifications_ai_chat_desc")
    val notificationsRewards: String get() = get("notifications_rewards")
    val notificationsRewardsDesc: String get() = get("notifications_rewards_desc")
    val notificationsDailyReminder: String get() = get("notifications_daily_reminder")
    val notificationsDailyReminderDesc: String get() = get("notifications_daily_reminder_desc")
    val notificationsSystem: String get() = get("notifications_system")
    val notificationsSystemDesc: String get() = get("notifications_system_desc")
    val notificationsSound: String get() = get("notifications_sound")
    val notificationsSoundDesc: String get() = get("notifications_sound_desc")
    val notificationsVibration: String get() = get("notifications_vibration")
    val notificationsVibrationDesc: String get() = get("notifications_vibration_desc")
    val notificationsDeliveryMethods: String get() = get("notifications_delivery_methods")
    val notificationsInfoText: String get() = get("notifications_info_text")
    val notificationsPermissionRequired: String get() = get("notifications_permission_required")
    val notificationsPermissionGoSettings: String get() = get("notifications_permission_go_settings")

    // ==================== 关于页面 ====================

    val aboutIntro: String get() = get("about_intro")
    val aboutVersionNameLabel: String get() = get("about_version_name_label")
    val aboutAppIntroTitle: String get() = get("about_app_intro_title")
    val stakingComingSoonToast: String get() = get("staking_coming_soon_toast")
    
    // ==================== AI 对话 ====================
    
    val chatTitle: String get() = get("chat_title")
    val chatPlaceholder: String get() = get("chat_placeholder")
    val chatSend: String get() = get("chat_send")
    val chatThinking: String get() = get("chat_thinking")
    val chatDecryptPrompt: String get() = get("chat_decrypt_prompt")
    val chatDecryptButton: String get() = get("chat_decrypt_button")
    val chatNoDecrypt: String get() = get("chat_no_decrypt")
    
    // ==================== 错误消息 ====================
    
    val errorNetwork: String get() = get("error_network")
    val errorNetworkDesc: String get() = get("error_network_desc")
    val errorWallet: String get() = get("error_wallet")
    val errorWalletDesc: String get() = get("error_wallet_desc")
    val errorAuth: String get() = get("error_auth")
    val errorAuthDesc: String get() = get("error_auth_desc")
    val errorStorage: String get() = get("error_storage")
    val errorStorageDesc: String get() = get("error_storage_desc")
    val errorUnknown: String get() = get("error_unknown")
    val errorUnknownDesc: String get() = get("error_unknown_desc")
    val errorCancelled: String get() = get("error_cancelled")
    val errorCancelledDesc: String get() = get("error_cancelled_desc")

    val biometricAuthRequiredTitle: String get() = get("biometric_auth_required_title")
    val biometricAuthRequiredSubtitle: String get() = get("biometric_auth_required_subtitle")
    val biometricDecryptTitle: String get() = get("biometric_decrypt_title")
    val biometricDecryptSubtitle: String get() = get("biometric_decrypt_subtitle")
    
    // ==================== 语言设置 ====================
    
    val languageSettings: String get() = get("language_settings")
    val languageCurrent: String get() = get("language_current")
    val languageChange: String get() = get("language_change")
    val languageChangeConfirm: String get() = get("language_change_confirm")
    
    // ==================== 设置页面 ====================
    
    val settingsLanguage: String get() = get("settings_language")
    val settingsTheme: String get() = get("settings_theme")
    val settingsClearCache: String get() = get("settings_clear_cache")
    val settingsPrivacyPolicy: String get() = get("settings_privacy_policy")
    val settingsTerms: String get() = get("settings_terms")
    
    // ==================== 游戏页面 ====================
    val gameBtnMarket: String get() = get("game_btn_market")
    val gameBtnSail: String get() = get("game_btn_sail")
    val gameBtnShipyard: String get() = get("game_btn_shipyard")
    val gameBtnComm: String get() = get("game_btn_comm")
    val gameBtnArchives: String get() = get("game_btn_archives")
    val gameBtnExit: String get() = get("game_btn_exit")
    val gameBtnBuy: String get() = get("game_btn_buy")
    val gameBtnSell: String get() = get("game_btn_sell")
    val gameBtnMint: String get() = get("game_btn_mint")
    val gameBtnInstall: String get() = get("game_btn_install")
    val gameBtnRetrofit: String get() = get("game_btn_retrofit")
    val gameBtnLeave: String get() = get("game_btn_leave")
    val gameBtnClose: String get() = get("game_btn_close")
    val gameBtnSend: String get() = get("game_btn_send")
    val gameBtnCloseLink: String get() = get("game_btn_close_link")
    val gameBtnTransmit: String get() = get("game_btn_transmit")
    val gameBtnJump: String get() = get("game_btn_jump")
    val gameTitleMarket: String get() = get("game_title_market")
    val gameTitleShipyard: String get() = get("game_title_shipyard")
    val gameTitleNav: String get() = get("game_title_nav")
    val gameTitleSeason: String get() = get("game_title_season")
    val gameTitleComm: String get() = get("game_title_comm")
    val gameTitleArchives: String get() = get("game_title_archives")
    val gameLabelPort: String get() = get("game_label_port")
    val gameLabelMoney: String get() = get("game_label_money")
    val gameLabelCargo: String get() = get("game_label_cargo")
    val gameLabelClass: String get() = get("game_label_class")
    val gameLabelUpgrades: String get() = get("game_label_upgrades")
    val gameLabelStock: String get() = get("game_label_stock")
    val gameLabelYouHave: String get() = get("game_label_you_have")
    val gameLabelCurrentLoc: String get() = get("game_label_current_loc")
    val gameLabelTravelCost: String get() = get("game_label_travel_cost")
    val gameLabelRisk: String get() = get("game_label_risk")
    val gameLabelCost: String get() = get("game_label_cost")
    val gameDialogExitTitle: String get() = get("game_dialog_exit_title")
    val gameDialogExitText: String get() = get("game_dialog_exit_text")
    val gameLogSystemBoot: String get() = get("game_log_system_boot")
    val gameLogConnectNet: String get() = get("game_log_connect_net")
    val gameLogAuthSuccess: String get() = get("game_log_auth_success")
    val gameLogPlayerSync: String get() = get("game_log_player_sync")
    val gameLogCurrentLoc: String get() = get("game_log_current_loc")
    val gameLogBalance: String get() = get("game_log_balance")
    val gameLogErrorData: String get() = get("game_log_error_data")
    val gameLogSeasonSync: String get() = get("game_log_season_sync")
    val gameLogMarketEvent: String get() = get("game_log_market_event")
    val gameLogNavOpen: String get() = get("game_log_nav_open")
    val gameLogSailStart: String get() = get("game_log_sail_start")
    val gameLogSailSuccess: String get() = get("game_log_sail_success")
    val gameLogSailFail: String get() = get("game_log_sail_fail")
    val gameLogBuySuccess: String get() = get("game_log_buy_success")
    val gameLogBuyFail: String get() = get("game_log_buy_fail")
    val gameLogSellSuccess: String get() = get("game_log_sell_success")
    val gameLogSellFail: String get() = get("game_log_sell_fail")
    val gameLogMintSuccess: String get() = get("game_log_mint_success")
    val gameLogMintFail: String get() = get("game_log_mint_fail")
    val gameLogUpgradeSuccess: String get() = get("game_log_upgrade_success")
    val gameLogUpgradeFail: String get() = get("game_log_upgrade_fail")
    val gameLogDungeonEnter: String get() = get("game_log_dungeon_enter")
    val gameLogDungeonWarn: String get() = get("game_log_dungeon_warn")
    val gameLogDungeonFail: String get() = get("game_log_dungeon_fail")
    val gameLogDungeonEnd: String get() = get("game_log_dungeon_end")
    val gameLogActionFail: String get() = get("game_log_action_fail")
    val gameLogContributeSuccess: String get() = get("game_log_contribute_success")
    val gameLogContributeFail: String get() = get("game_log_contribute_fail")
    
    val gameLabelVesselStatus: String get() = get("game_label_vessel_status")
    val gameDescCargoUpgrade: String get() = get("game_desc_cargo_upgrade")
    val gameDescShipUpgrade: String get() = get("game_desc_ship_upgrade")
    val gameIntro1: String get() = get("game_intro_1")
    val gameIntro2: String get() = get("game_intro_2")
    val gameIntro3: String get() = get("game_intro_3")
    val gameIntro4: String get() = get("game_intro_4")
    val gameIntro5: String get() = get("game_intro_5")
    
    // ==================== 翻译映射 ====================
    
    private fun get(key: String): String {
        return resolve(key, currentLanguageCode)
    }
    
    private val translations: Map<String, Map<String, String>> = mapOf(
        // 通用
        "app_name" to mapOf(
            "zh" to "Soulon",
            "en" to "Soulon",
            "ja" to "Soulon",
            "ko" to "Soulon",
            "es" to "Soulon",
            "fr" to "Soulon",
            "de" to "Soulon",
            "pt" to "Soulon",
            "ru" to "Soulon",
            "ar" to "Soulon"
        ),
        "confirm" to mapOf(
            "zh" to "确定",
            "en" to "Confirm",
            "ja" to "確認",
            "ko" to "확인",
            "es" to "Confirmar",
            "fr" to "Confirmer",
            "de" to "Bestätigen",
            "pt" to "Confirmar",
            "ru" to "Подтвердить",
            "ar" to "تأكيد"
        ),
        "cancel" to mapOf(
            "zh" to "取消",
            "en" to "Cancel",
            "ja" to "キャンセル",
            "ko" to "취소",
            "es" to "Cancelar",
            "fr" to "Annuler",
            "de" to "Abbrechen",
            "pt" to "Cancelar",
            "ru" to "Отмена",
            "ar" to "إلغاء"
        ),
        "biometric_auth_required_title" to mapOf(
            "zh" to "需要身份验证",
            "en" to "Authentication Required",
            "ja" to "Authentication Required",
            "ko" to "Authentication Required",
            "es" to "Authentication Required",
            "fr" to "Authentication Required",
            "de" to "Authentication Required",
            "pt" to "Authentication Required",
            "ru" to "Authentication Required",
            "ar" to "Authentication Required"
        ),
        "biometric_auth_required_subtitle" to mapOf(
            "zh" to "请使用指纹或设备凭据确认",
            "en" to "Use biometrics or device credentials to continue",
            "ja" to "Use biometrics or device credentials to continue",
            "ko" to "Use biometrics or device credentials to continue",
            "es" to "Use biometrics or device credentials to continue",
            "fr" to "Use biometrics or device credentials to continue",
            "de" to "Use biometrics or device credentials to continue",
            "pt" to "Use biometrics or device credentials to continue",
            "ru" to "Use biometrics or device credentials to continue",
            "ar" to "Use biometrics or device credentials to continue"
        ),
        "biometric_decrypt_title" to mapOf(
            "zh" to "解密记忆",
            "en" to "Decrypt Memories",
            "ja" to "Decrypt Memories",
            "ko" to "Decrypt Memories",
            "es" to "Decrypt Memories",
            "fr" to "Decrypt Memories",
            "de" to "Decrypt Memories",
            "pt" to "Decrypt Memories",
            "ru" to "Decrypt Memories",
            "ar" to "Decrypt Memories"
        ),
        "biometric_decrypt_subtitle" to mapOf(
            "zh" to "验证身份以批量解密您的记忆",
            "en" to "Verify your identity to decrypt your memories",
            "ja" to "Verify your identity to decrypt your memories",
            "ko" to "Verify your identity to decrypt your memories",
            "es" to "Verify your identity to decrypt your memories",
            "fr" to "Verify your identity to decrypt your memories",
            "de" to "Verify your identity to decrypt your memories",
            "pt" to "Verify your identity to decrypt your memories",
            "ru" to "Verify your identity to decrypt your memories",
            "ar" to "Verify your identity to decrypt your memories"
        ),
        "back" to mapOf(
            "zh" to "返回",
            "en" to "Back",
            "ja" to "戻る",
            "ko" to "뒤로",
            "es" to "Atrás",
            "fr" to "Retour",
            "de" to "Zurück",
            "pt" to "Voltar",
            "ru" to "Назад",
            "ar" to "رجوع"
        ),
        "retry" to mapOf(
            "zh" to "重试",
            "en" to "Retry",
            "ja" to "再試行",
            "ko" to "재시도",
            "es" to "Reintentar",
            "fr" to "Réessayer",
            "de" to "Wiederholen",
            "pt" to "Tentar novamente",
            "ru" to "Повторить",
            "ar" to "إعادة المحاولة"
        ),
        "loading" to mapOf(
            "zh" to "加载中...",
            "en" to "Loading...",
            "ja" to "読み込み中...",
            "ko" to "로딩 중...",
            "es" to "Cargando...",
            "fr" to "Chargement...",
            "de" to "Laden...",
            "pt" to "Carregando...",
            "ru" to "Загрузка...",
            "ar" to "جار التحميل..."
        ),
        "save" to mapOf(
            "zh" to "保存",
            "en" to "Save",
            "ja" to "保存",
            "ko" to "저장",
            "es" to "Guardar",
            "fr" to "Enregistrer",
            "de" to "Speichern",
            "pt" to "Salvar",
            "ru" to "Сохранить",
            "ar" to "حفظ"
        ),
        "delete" to mapOf(
            "zh" to "删除",
            "en" to "Delete",
            "ja" to "削除",
            "ko" to "삭제",
            "es" to "Eliminar",
            "fr" to "Supprimer",
            "de" to "Löschen",
            "pt" to "Excluir",
            "ru" to "Удалить",
            "ar" to "حذف"
        ),
        "edit" to mapOf(
            "zh" to "编辑",
            "en" to "Edit",
            "ja" to "編集",
            "ko" to "편집",
            "es" to "Editar",
            "fr" to "Modifier",
            "de" to "Bearbeiten",
            "pt" to "Editar",
            "ru" to "Редактировать",
            "ar" to "تعديل"
        ),
        "done" to mapOf(
            "zh" to "完成",
            "en" to "Done",
            "ja" to "完了",
            "ko" to "완료",
            "es" to "Hecho",
            "fr" to "Terminé",
            "de" to "Fertig",
            "pt" to "Concluído",
            "ru" to "Готово",
            "ar" to "تم"
        ),
        "next" to mapOf(
            "zh" to "下一步",
            "en" to "Next",
            "ja" to "次へ",
            "ko" to "다음",
            "es" to "Siguiente",
            "fr" to "Suivant",
            "de" to "Weiter",
            "pt" to "Próximo",
            "ru" to "Далее",
            "ar" to "التالي"
        ),
        "skip" to mapOf(
            "zh" to "跳过",
            "en" to "Skip",
            "ja" to "スキップ",
            "ko" to "건너뛰기",
            "es" to "Omitir",
            "fr" to "Passer",
            "de" to "Überspringen",
            "pt" to "Pular",
            "ru" to "Пропустить",
            "ar" to "تخطي"
        ),
        "close" to mapOf(
            "zh" to "关闭",
            "en" to "Close",
            "ja" to "閉じる",
            "ko" to "닫기",
            "es" to "Cerrar",
            "fr" to "Fermer",
            "de" to "Schließen",
            "pt" to "Fechar",
            "ru" to "Закрыть",
            "ar" to "إغلاق"
        ),
        "search" to mapOf(
            "zh" to "搜索",
            "en" to "Search",
            "ja" to "検索",
            "ko" to "검색",
            "es" to "Buscar",
            "fr" to "Rechercher",
            "de" to "Suchen",
            "pt" to "Pesquisar",
            "ru" to "Поиск",
            "ar" to "بحث"
        ),
        "settings" to mapOf(
            "zh" to "设置",
            "en" to "Settings",
            "ja" to "設定",
            "ko" to "설정",
            "es" to "Configuración",
            "fr" to "Paramètres",
            "de" to "Einstellungen",
            "pt" to "Configurações",
            "ru" to "Настройки",
            "ar" to "الإعدادات"
        ),
        "about" to mapOf(
            "zh" to "关于",
            "en" to "About",
            "ja" to "について",
            "ko" to "정보",
            "es" to "Acerca de",
            "fr" to "À propos",
            "de" to "Über",
            "pt" to "Sobre",
            "ru" to "О приложении",
            "ar" to "حول"
        ),
        "help" to mapOf(
            "zh" to "帮助",
            "en" to "Help",
            "ja" to "ヘルプ",
            "ko" to "도움말",
            "es" to "Ayuda",
            "fr" to "Aide",
            "de" to "Hilfe",
            "pt" to "Ajuda",
            "ru" to "Помощь",
            "ar" to "مساعدة"
        ),
        "continue" to mapOf(
            "zh" to "继续",
            "en" to "Continue",
            "ja" to "続ける",
            "ko" to "계속",
            "es" to "Continuar",
            "fr" to "Continuer",
            "de" to "Fortfahren",
            "pt" to "Continuar",
            "ru" to "Продолжить",
            "ar" to "متابعة"
        ),
        
        // 钱包连接
        "welcome_title" to mapOf(
            "zh" to "欢迎使用 Soulon",
            "en" to "Welcome to Soulon",
            "ja" to "Soulon へようこそ",
            "ko" to "Soulon에 오신 것을 환영합니다",
            "es" to "Bienvenido a Soulon",
            "fr" to "Bienvenue sur Soulon",
            "de" to "Willkommen bei Soulon",
            "pt" to "Bem-vindo ao Soulon",
            "ru" to "Добро пожаловать в Soulon",
            "ar" to "مرحباً بك في Soulon"
        ),
        "welcome_subtitle" to mapOf(
            "zh" to "连接你的 Solana 钱包\n开始去中心化记忆之旅",
            "en" to "Connect your Solana wallet\nStart your decentralized memory journey",
            "ja" to "Solanaウォレットを接続して\n分散型メモリーの旅を始めましょう",
            "ko" to "Solana 지갑을 연결하고\n탈중앙화 메모리 여정을 시작하세요",
            "es" to "Conecta tu billetera Solana\nComienza tu viaje de memoria descentralizada",
            "fr" to "Connectez votre portefeuille Solana\nCommencez votre voyage de mémoire décentralisée",
            "de" to "Verbinde deine Solana-Wallet\nStarte deine dezentrale Erinnerungsreise",
            "pt" to "Conecte sua carteira Solana\nInicie sua jornada de memória descentralizada",
            "ru" to "Подключите кошелек Solana\nНачните децентрализованное путешествие памяти",
            "ar" to "اربط محفظة Solana الخاصة بك\nابدأ رحلة الذاكرة اللامركزية"
        ),
        "connect_wallet" to mapOf(
            "zh" to "连接钱包",
            "en" to "Connect Wallet",
            "ja" to "ウォレットを接続",
            "ko" to "지갑 연결",
            "es" to "Conectar billetera",
            "fr" to "Connecter le portefeuille",
            "de" to "Wallet verbinden",
            "pt" to "Conectar carteira",
            "ru" to "Подключить кошелек",
            "ar" to "ربط المحفظة"
        ),
        "connecting" to mapOf(
            "zh" to "正在连接...",
            "en" to "Connecting...",
            "ja" to "接続中...",
            "ko" to "연결 중...",
            "es" to "Conectando...",
            "fr" to "Connexion...",
            "de" to "Verbinden...",
            "pt" to "Conectando...",
            "ru" to "Подключение...",
            "ar" to "جاري الاتصال..."
        ),
        "retry_connect" to mapOf(
            "zh" to "重试连接",
            "en" to "Retry Connection",
            "ja" to "再接続",
            "ko" to "재연결",
            "es" to "Reintentar conexión",
            "fr" to "Réessayer la connexion",
            "de" to "Verbindung wiederholen",
            "pt" to "Tentar novamente",
            "ru" to "Повторить подключение",
            "ar" to "إعادة الاتصال"
        ),
        "wallet_support" to mapOf(
            "zh" to "支持 Phantom、Solflare 等 Solana 钱包",
            "en" to "Supports Phantom, Solflare and other Solana wallets",
            "ja" to "Phantom、Solflareなどのソラナウォレットをサポート",
            "ko" to "Phantom, Solflare 등 Solana 지갑 지원",
            "es" to "Compatible con Phantom, Solflare y otras billeteras Solana",
            "fr" to "Prend en charge Phantom, Solflare et autres portefeuilles Solana",
            "de" to "Unterstützt Phantom, Solflare und andere Solana-Wallets",
            "pt" to "Suporta Phantom, Solflare e outras carteiras Solana",
            "ru" to "Поддерживает Phantom, Solflare и другие кошельки Solana",
            "ar" to "يدعم Phantom و Solflare ومحافظ Solana الأخرى"
        ),
        "feature_encryption" to mapOf(
            "zh" to "安全加密",
            "en" to "Secure Encryption",
            "ja" to "安全な暗号化",
            "ko" to "보안 암호화",
            "es" to "Cifrado seguro",
            "fr" to "Chiffrement sécurisé",
            "de" to "Sichere Verschlüsselung",
            "pt" to "Criptografia segura",
            "ru" to "Безопасное шифрование",
            "ar" to "تشفير آمن"
        ),
        "feature_encryption_desc" to mapOf(
            "zh" to "你的记忆通过端对端加密保护，只有你能访问",
            "en" to "Your memories are protected by end-to-end encryption, only you can access",
            "ja" to "あなたの記憶はエンドツーエンド暗号化で保護され、あなただけがアクセスできます",
            "ko" to "당신의 기억은 엔드투엔드 암호화로 보호되며, 오직 당신만 접근할 수 있습니다",
            "es" to "Tus recuerdos están protegidos con cifrado de extremo a extremo, solo tú puedes acceder",
            "fr" to "Vos souvenirs sont protégés par un chiffrement de bout en bout, vous seul pouvez y accéder",
            "de" to "Deine Erinnerungen sind durch Ende-zu-Ende-Verschlüsselung geschützt, nur du kannst darauf zugreifen",
            "pt" to "Suas memórias são protegidas por criptografia ponta a ponta, só você pode acessar",
            "ru" to "Ваши воспоминания защищены сквозным шифрованием, только вы можете получить доступ",
            "ar" to "ذكرياتك محمية بالتشفير من طرف إلى طرف، أنت وحدك من يمكنه الوصول"
        ),
        "feature_storage" to mapOf(
            "zh" to "永久存储",
            "en" to "Permanent Storage",
            "ja" to "永続ストレージ",
            "ko" to "영구 저장",
            "es" to "Almacenamiento permanente",
            "fr" to "Stockage permanent",
            "de" to "Permanenter Speicher",
            "pt" to "Armazenamento permanente",
            "ru" to "Постоянное хранение",
            "ar" to "تخزين دائم"
        ),
        "feature_storage_desc" to mapOf(
            "zh" to "记忆上传到 Arweave 网络，永久保存，永不丢失",
            "en" to "Memories uploaded to Arweave network, permanently saved, never lost",
            "ja" to "記憶はArweaveネットワークにアップロードされ、永久に保存され、失われることはありません",
            "ko" to "기억이 Arweave 네트워크에 업로드되어 영구적으로 저장되며, 절대 잃어버리지 않습니다",
            "es" to "Recuerdos subidos a la red Arweave, guardados permanentemente, nunca se pierden",
            "fr" to "Souvenirs téléchargés sur le réseau Arweave, sauvegardés en permanence, jamais perdus",
            "de" to "Erinnerungen werden ins Arweave-Netzwerk hochgeladen, dauerhaft gespeichert, nie verloren",
            "pt" to "Memórias enviadas para a rede Arweave, salvas permanentemente, nunca perdidas",
            "ru" to "Воспоминания загружаются в сеть Arweave, сохраняются навсегда, никогда не теряются",
            "ar" to "يتم تحميل الذكريات إلى شبكة Arweave، وحفظها بشكل دائم، ولن تضيع أبداً"
        ),
        "feature_decentralized" to mapOf(
            "zh" to "完全去中心化",
            "en" to "Fully Decentralized",
            "ja" to "完全に分散化",
            "ko" to "완전한 탈중앙화",
            "es" to "Totalmente descentralizado",
            "fr" to "Entièrement décentralisé",
            "de" to "Vollständig dezentralisiert",
            "pt" to "Totalmente descentralizado",
            "ru" to "Полностью децентрализовано",
            "ar" to "لامركزي بالكامل"
        ),
        "feature_decentralized_desc" to mapOf(
            "zh" to "使用 Solana 区块链，记忆铸造为 cNFT，真正属于你",
            "en" to "Using Solana blockchain, memories minted as cNFT, truly belong to you",
            "ja" to "Solanaブロックチェーンを使用し、記憶はcNFTとして鋳造され、本当にあなたのものです",
            "ko" to "Solana 블록체인을 사용하여 기억이 cNFT로 발행되며, 진정으로 당신의 것입니다",
            "es" to "Usando blockchain Solana, recuerdos acuñados como cNFT, realmente te pertenecen",
            "fr" to "Utilisant la blockchain Solana, souvenirs frappés en cNFT, vous appartiennent vraiment",
            "de" to "Mit Solana-Blockchain, Erinnerungen als cNFT geprägt, gehören wirklich dir",
            "pt" to "Usando blockchain Solana, memórias cunhadas como cNFT, realmente pertencem a você",
            "ru" to "Использует блокчейн Solana, воспоминания чеканятся как cNFT, действительно принадлежат вам",
            "ar" to "باستخدام بلوكتشين Solana، يتم سك الذكريات كـ cNFT، تنتمي إليك حقاً"
        ),
        "feature_ownership" to mapOf(
            "zh" to "完全自主",
            "en" to "Full Ownership",
            "ja" to "完全な所有権",
            "ko" to "완전한 소유권",
            "es" to "Propiedad Total",
            "fr" to "Propriété Totale",
            "de" to "Volle Eigentümerschaft",
            "pt" to "Propriedade Total",
            "ru" to "Полное владение",
            "ar" to "ملكية كاملة"
        ),
        "feature_ownership_desc" to mapOf(
            "zh" to "只有你能访问和解密你的记忆，完全私密",
            "en" to "Only you can access and decrypt your memories, completely private",
            "ja" to "あなただけがあなたの記憶にアクセスして復号化できます、完全にプライベート",
            "ko" to "당신만이 당신의 기억에 접근하고 해독할 수 있습니다, 완전히 비공개",
            "es" to "Solo tú puedes acceder y descifrar tus recuerdos, completamente privado",
            "fr" to "Seul vous pouvez accéder et déchiffrer vos souvenirs, totalement privé",
            "de" to "Nur Sie können auf Ihre Erinnerungen zugreifen und sie entschlüsseln, vollständig privat",
            "pt" to "Só você pode acessar e descriptografar suas memórias, completamente privado",
            "ru" to "Только вы можете получить доступ и расшифровать свои воспоминания, полностью приватно",
            "ar" to "أنت فقط يمكنك الوصول وفك تشفير ذكرياتك، خاص تماماً"
        ),
        "supported_wallets" to mapOf(
            "zh" to "支持 Phantom、Solflare 等 Solana 钱包",
            "en" to "Supports Phantom, Solflare and other Solana wallets",
            "ja" to "Phantom、Solflareなどの Solana ウォレットをサポート",
            "ko" to "Phantom, Solflare 및 기타 Solana 지갑 지원",
            "es" to "Compatible con Phantom, Solflare y otras billeteras Solana",
            "fr" to "Prend en charge Phantom, Solflare et autres portefeuilles Solana",
            "de" to "Unterstützt Phantom, Solflare und andere Solana-Wallets",
            "pt" to "Suporta Phantom, Solflare e outras carteiras Solana",
            "ru" to "Поддерживает Phantom, Solflare и другие кошельки Solana",
            "ar" to "يدعم Phantom وSolflare ومحافظ Solana الأخرى"
        ),
        "language_welcome_title" to mapOf(
            "zh" to "欢迎",
            "en" to "Welcome",
            "ja" to "ようこそ",
            "ko" to "환영합니다",
            "es" to "Bienvenido",
            "fr" to "Bienvenue",
            "de" to "Willkommen",
            "pt" to "Bem-vindo",
            "ru" to "Добро пожаловать",
            "ar" to "مرحبًا"
        ),
        "language_welcome_subtitle" to mapOf(
            "zh" to "选择您的语言",
            "en" to "Choose your language",
            "ja" to "言語を選択してください",
            "ko" to "언어를 선택하세요",
            "es" to "Elige tu idioma",
            "fr" to "Choisissez votre langue",
            "de" to "Wähle deine Sprache",
            "pt" to "Escolha seu idioma",
            "ru" to "Выберите язык",
            "ar" to "اختر لغتك"
        ),
        
        // 导航
        "nav_dashboard" to mapOf(
            "zh" to "首页",
            "en" to "Home",
            "ja" to "ホーム",
            "ko" to "홈",
            "es" to "Inicio",
            "fr" to "Accueil",
            "de" to "Startseite",
            "pt" to "Início",
            "ru" to "Главная",
            "ar" to "الرئيسية"
        ),
        "nav_chat" to mapOf(
            "zh" to "AI对话",
            "en" to "AI Chat",
            "ja" to "AIチャット",
            "ko" to "AI 채팅",
            "es" to "Chat IA",
            "fr" to "Chat IA",
            "de" to "KI-Chat",
            "pt" to "Chat IA",
            "ru" to "ИИ-чат",
            "ar" to "دردشة AI"
        ),
        "nav_profile" to mapOf(
            "zh" to "我的",
            "en" to "Me",
            "ja" to "マイページ",
            "ko" to "내 정보",
            "es" to "Yo",
            "fr" to "Moi",
            "de" to "Ich",
            "pt" to "Eu",
            "ru" to "Я",
            "ar" to "أنا"
        ),
        
        // 我的页面
        "profile_sync" to mapOf(
            "zh" to "跨设备同步",
            "en" to "Cross-Device Sync",
            "ja" to "クロスデバイス同期",
            "ko" to "기기 간 동기화",
            "es" to "Sincronización entre dispositivos",
            "fr" to "Synchronisation multi-appareils",
            "de" to "Geräteübergreifende Synchronisierung",
            "pt" to "Sincronização entre dispositivos",
            "ru" to "Синхронизация между устройствами",
            "ar" to "المزامنة عبر الأجهزة"
        ),
        "profile_sync_desc" to mapOf(
            "zh" to "备份和迁移记忆到新设备",
            "en" to "Backup and migrate memories to new device",
            "ja" to "新しいデバイスにメモリをバックアップして移行",
            "ko" to "새 기기로 기억 백업 및 마이그레이션",
            "es" to "Respaldar y migrar recuerdos a un nuevo dispositivo",
            "fr" to "Sauvegarder et migrer les souvenirs vers un nouvel appareil",
            "de" to "Erinnerungen sichern und auf neues Gerät migrieren",
            "pt" to "Fazer backup e migrar memórias para novo dispositivo",
            "ru" to "Резервное копирование и миграция воспоминаний на новое устройство",
            "ar" to "نسخ احتياطي ونقل الذكريات إلى جهاز جديد"
        ),
        "profile_language" to mapOf(
            "zh" to "语言",
            "en" to "Language",
            "ja" to "言語",
            "ko" to "언어",
            "es" to "Idioma",
            "fr" to "Langue",
            "de" to "Sprache",
            "pt" to "Idioma",
            "ru" to "Язык",
            "ar" to "اللغة"
        ),
        "profile_about" to mapOf(
            "zh" to "关于",
            "en" to "About",
            "ja" to "アプリについて",
            "ko" to "앱 정보",
            "es" to "Acerca de",
            "fr" to "À propos",
            "de" to "Über",
            "pt" to "Sobre",
            "ru" to "О приложении",
            "ar" to "حول"
        ),
        "profile_about_desc" to mapOf(
            "zh" to "版本信息与应用介绍",
            "en" to "Version info and app introduction",
            "ja" to "バージョン情報とアプリ紹介",
            "ko" to "버전 정보 및 앱 소개",
            "es" to "Información de versión e introducción de la app",
            "fr" to "Informations de version et présentation de l'app",
            "de" to "Versionsinformationen und App-Einführung",
            "pt" to "Informações de versão e introdução do app",
            "ru" to "Информация о версии и описание приложения",
            "ar" to "معلومات الإصدار ومقدمة التطبيق"
        ),
        "profile_faq" to mapOf(
            "zh" to "常见问题",
            "en" to "FAQ",
            "ja" to "よくある質問",
            "ko" to "자주 묻는 질문",
            "es" to "Preguntas frecuentes",
            "fr" to "FAQ",
            "de" to "FAQ",
            "pt" to "Perguntas frequentes",
            "ru" to "Частые вопросы",
            "ar" to "الأسئلة الشائعة"
        ),
        "profile_faq_desc" to mapOf(
            "zh" to "使用帮助与问题解答",
            "en" to "Help and answers",
            "ja" to "ヘルプと回答",
            "ko" to "도움말 및 답변",
            "es" to "Ayuda y respuestas",
            "fr" to "Aide et réponses",
            "de" to "Hilfe und Antworten",
            "pt" to "Ajuda e respostas",
            "ru" to "Помощь и ответы",
            "ar" to "المساعدة والإجابات"
        ),
        "profile_settings" to mapOf(
            "zh" to "设置",
            "en" to "Settings",
            "ja" to "設定",
            "ko" to "설정",
            "es" to "Configuración",
            "fr" to "Paramètres",
            "de" to "Einstellungen",
            "pt" to "Configurações",
            "ru" to "Настройки",
            "ar" to "الإعدادات"
        ),
        "profile_settings_desc" to mapOf(
            "zh" to "应用偏好与配置",
            "en" to "App preferences and configuration",
            "ja" to "アプリの設定と構成",
            "ko" to "앱 환경설정 및 구성",
            "es" to "Preferencias y configuración de la app",
            "fr" to "Préférences et configuration de l'app",
            "de" to "App-Einstellungen und Konfiguration",
            "pt" to "Preferências e configuração do app",
            "ru" to "Настройки и конфигурация приложения",
            "ar" to "تفضيلات التطبيق والإعدادات"
        ),
        "profile_section_preferences" to mapOf(
            "zh" to "偏好设置",
            "en" to "Preferences",
            "ja" to "Preferences",
            "ko" to "Preferences",
            "es" to "Preferences",
            "fr" to "Preferences",
            "de" to "Preferences",
            "pt" to "Preferences",
            "ru" to "Preferences",
            "ar" to "Preferences"
        ),
        "profile_notifications" to mapOf(
            "zh" to "通知",
            "en" to "Notifications",
            "ja" to "Notifications",
            "ko" to "Notifications",
            "es" to "Notifications",
            "fr" to "Notifications",
            "de" to "Notifications",
            "pt" to "Notifications",
            "ru" to "Notifications",
            "ar" to "Notifications"
        ),
        "profile_notifications_desc" to mapOf(
            "zh" to "管理推送通知和提醒",
            "en" to "Manage push notifications and reminders",
            "ja" to "Manage push notifications and reminders",
            "ko" to "Manage push notifications and reminders",
            "es" to "Manage push notifications and reminders",
            "fr" to "Manage push notifications and reminders",
            "de" to "Manage push notifications and reminders",
            "pt" to "Manage push notifications and reminders",
            "ru" to "Manage push notifications and reminders",
            "ar" to "Manage push notifications and reminders"
        ),
        "profile_security" to mapOf(
            "zh" to "安全",
            "en" to "Security",
            "ja" to "Security",
            "ko" to "Security",
            "es" to "Security",
            "fr" to "Security",
            "de" to "Security",
            "pt" to "Security",
            "ru" to "Security",
            "ar" to "Security"
        ),
        "profile_security_desc" to mapOf(
            "zh" to "身份认证、数据同步",
            "en" to "Identity and data protection",
            "ja" to "Identity and data protection",
            "ko" to "Identity and data protection",
            "es" to "Identity and data protection",
            "fr" to "Identity and data protection",
            "de" to "Identity and data protection",
            "pt" to "Identity and data protection",
            "ru" to "Identity and data protection",
            "ar" to "Identity and data protection"
        ),
        "profile_section_help_support" to mapOf(
            "zh" to "帮助 & 支持",
            "en" to "Help & Support",
            "ja" to "Help & Support",
            "ko" to "Help & Support",
            "es" to "Help & Support",
            "fr" to "Help & Support",
            "de" to "Help & Support",
            "pt" to "Help & Support",
            "ru" to "Help & Support",
            "ar" to "Help & Support"
        ),
        "profile_bug_report" to mapOf(
            "zh" to "Bug 报告",
            "en" to "Bug Report",
            "ja" to "Bug Report",
            "ko" to "Bug Report",
            "es" to "Bug Report",
            "fr" to "Bug Report",
            "de" to "Bug Report",
            "pt" to "Bug Report",
            "ru" to "Bug Report",
            "ar" to "Bug Report"
        ),
        "profile_bug_report_desc" to mapOf(
            "zh" to "报告问题帮助我们改进",
            "en" to "Report an issue to help us improve",
            "ja" to "Report an issue to help us improve",
            "ko" to "Report an issue to help us improve",
            "es" to "Report an issue to help us improve",
            "fr" to "Report an issue to help us improve",
            "de" to "Report an issue to help us improve",
            "pt" to "Report an issue to help us improve",
            "ru" to "Report an issue to help us improve",
            "ar" to "Report an issue to help us improve"
        ),
        "profile_contact_us" to mapOf(
            "zh" to "联系我们",
            "en" to "Contact Us",
            "ja" to "Contact Us",
            "ko" to "Contact Us",
            "es" to "Contact Us",
            "fr" to "Contact Us",
            "de" to "Contact Us",
            "pt" to "Contact Us",
            "ru" to "Contact Us",
            "ar" to "Contact Us"
        ),
        "profile_contact_us_desc" to mapOf(
            "zh" to "获取帮助或提供反馈",
            "en" to "Get help or share feedback",
            "ja" to "Get help or share feedback",
            "ko" to "Get help or share feedback",
            "es" to "Get help or share feedback",
            "fr" to "Get help or share feedback",
            "de" to "Get help or share feedback",
            "pt" to "Get help or share feedback",
            "ru" to "Get help or share feedback",
            "ar" to "Get help or share feedback"
        ),

        // 通知设置
        "notifications_title" to mapOf(
            "zh" to "通知",
            "en" to "Notifications",
            "ja" to "Notifications",
            "ko" to "Notifications",
            "es" to "Notifications",
            "fr" to "Notifications",
            "de" to "Notifications",
            "pt" to "Notifications",
            "ru" to "Notifications",
            "ar" to "Notifications"
        ),
        "notifications_push" to mapOf(
            "zh" to "推送通知",
            "en" to "Push Notifications",
            "ja" to "Push Notifications",
            "ko" to "Push Notifications",
            "es" to "Push Notifications",
            "fr" to "Push Notifications",
            "de" to "Push Notifications",
            "pt" to "Push Notifications",
            "ru" to "Push Notifications",
            "ar" to "Push Notifications"
        ),
        "notifications_push_desc" to mapOf(
            "zh" to "接收应用的推送通知",
            "en" to "Receive push notifications",
            "ja" to "Receive push notifications",
            "ko" to "Receive push notifications",
            "es" to "Receive push notifications",
            "fr" to "Receive push notifications",
            "de" to "Receive push notifications",
            "pt" to "Receive push notifications",
            "ru" to "Receive push notifications",
            "ar" to "Receive push notifications"
        ),
        "notifications_message_types" to mapOf(
            "zh" to "消息类型",
            "en" to "Message Types",
            "ja" to "Message Types",
            "ko" to "Message Types",
            "es" to "Message Types",
            "fr" to "Message Types",
            "de" to "Message Types",
            "pt" to "Message Types",
            "ru" to "Message Types",
            "ar" to "Message Types"
        ),
        "notifications_adventure" to mapOf(
            "zh" to "奇遇任务",
            "en" to "Adventure Tasks",
            "ja" to "Adventure Tasks",
            "ko" to "Adventure Tasks",
            "es" to "Adventure Tasks",
            "fr" to "Adventure Tasks",
            "de" to "Adventure Tasks",
            "pt" to "Adventure Tasks",
            "ru" to "Adventure Tasks",
            "ar" to "Adventure Tasks"
        ),
        "notifications_adventure_desc" to mapOf(
            "zh" to "随机不定时推送，完成可获大量积分",
            "en" to "Occasional prompts with rewards",
            "ja" to "Occasional prompts with rewards",
            "ko" to "Occasional prompts with rewards",
            "es" to "Occasional prompts with rewards",
            "fr" to "Occasional prompts with rewards",
            "de" to "Occasional prompts with rewards",
            "pt" to "Occasional prompts with rewards",
            "ru" to "Occasional prompts with rewards",
            "ar" to "Occasional prompts with rewards"
        ),
        "notifications_ai_chat" to mapOf(
            "zh" to "AI 对话",
            "en" to "AI Chat",
            "ja" to "AI Chat",
            "ko" to "AI Chat",
            "es" to "AI Chat",
            "fr" to "AI Chat",
            "de" to "AI Chat",
            "pt" to "AI Chat",
            "ru" to "AI Chat",
            "ar" to "AI Chat"
        ),
        "notifications_ai_chat_desc" to mapOf(
            "zh" to "AI 回复或提醒时通知",
            "en" to "Alerts for replies and reminders",
            "ja" to "Alerts for replies and reminders",
            "ko" to "Alerts for replies and reminders",
            "es" to "Alerts for replies and reminders",
            "fr" to "Alerts for replies and reminders",
            "de" to "Alerts for replies and reminders",
            "pt" to "Alerts for replies and reminders",
            "ru" to "Alerts for replies and reminders",
            "ar" to "Alerts for replies and reminders"
        ),
        "notifications_rewards" to mapOf(
            "zh" to "奖励提醒",
            "en" to "Rewards",
            "ja" to "Rewards",
            "ko" to "Rewards",
            "es" to "Rewards",
            "fr" to "Rewards",
            "de" to "Rewards",
            "pt" to "Rewards",
            "ru" to "Rewards",
            "ar" to "Rewards"
        ),
        "notifications_rewards_desc" to mapOf(
            "zh" to "获得积分或升级时通知",
            "en" to "Alerts for points and level-ups",
            "ja" to "Alerts for points and level-ups",
            "ko" to "Alerts for points and level-ups",
            "es" to "Alerts for points and level-ups",
            "fr" to "Alerts for points and level-ups",
            "de" to "Alerts for points and level-ups",
            "pt" to "Alerts for points and level-ups",
            "ru" to "Alerts for points and level-ups",
            "ar" to "Alerts for points and level-ups"
        ),
        "notifications_daily_reminder" to mapOf(
            "zh" to "每日提醒",
            "en" to "Daily Reminder",
            "ja" to "Daily Reminder",
            "ko" to "Daily Reminder",
            "es" to "Daily Reminder",
            "fr" to "Daily Reminder",
            "de" to "Daily Reminder",
            "pt" to "Daily Reminder",
            "ru" to "Daily Reminder",
            "ar" to "Daily Reminder"
        ),
        "notifications_daily_reminder_desc" to mapOf(
            "zh" to "每日签到与活动提醒",
            "en" to "Check-in and activity reminders",
            "ja" to "Check-in and activity reminders",
            "ko" to "Check-in and activity reminders",
            "es" to "Check-in and activity reminders",
            "fr" to "Check-in and activity reminders",
            "de" to "Check-in and activity reminders",
            "pt" to "Check-in and activity reminders",
            "ru" to "Check-in and activity reminders",
            "ar" to "Check-in and activity reminders"
        ),
        "notifications_system" to mapOf(
            "zh" to "系统通知",
            "en" to "System",
            "ja" to "System",
            "ko" to "System",
            "es" to "System",
            "fr" to "System",
            "de" to "System",
            "pt" to "System",
            "ru" to "System",
            "ar" to "System"
        ),
        "notifications_system_desc" to mapOf(
            "zh" to "重要更新与系统消息",
            "en" to "Important updates and system messages",
            "ja" to "Important updates and system messages",
            "ko" to "Important updates and system messages",
            "es" to "Important updates and system messages",
            "fr" to "Important updates and system messages",
            "de" to "Important updates and system messages",
            "pt" to "Important updates and system messages",
            "ru" to "Important updates and system messages",
            "ar" to "Important updates and system messages"
        ),
        "notifications_sound" to mapOf(
            "zh" to "声音",
            "en" to "Sound",
            "ja" to "Sound",
            "ko" to "Sound",
            "es" to "Sound",
            "fr" to "Sound",
            "de" to "Sound",
            "pt" to "Sound",
            "ru" to "Sound",
            "ar" to "Sound"
        ),
        "notifications_sound_desc" to mapOf(
            "zh" to "通知提示音",
            "en" to "Notification sounds",
            "ja" to "Notification sounds",
            "ko" to "Notification sounds",
            "es" to "Notification sounds",
            "fr" to "Notification sounds",
            "de" to "Notification sounds",
            "pt" to "Notification sounds",
            "ru" to "Notification sounds",
            "ar" to "Notification sounds"
        ),
        "notifications_vibration" to mapOf(
            "zh" to "震动",
            "en" to "Vibration",
            "ja" to "Vibration",
            "ko" to "Vibration",
            "es" to "Vibration",
            "fr" to "Vibration",
            "de" to "Vibration",
            "pt" to "Vibration",
            "ru" to "Vibration",
            "ar" to "Vibration"
        ),
        "notifications_vibration_desc" to mapOf(
            "zh" to "通知震动反馈",
            "en" to "Vibration feedback",
            "ja" to "Vibration feedback",
            "ko" to "Vibration feedback",
            "es" to "Vibration feedback",
            "fr" to "Vibration feedback",
            "de" to "Vibration feedback",
            "pt" to "Vibration feedback",
            "ru" to "Vibration feedback",
            "ar" to "Vibration feedback"
        ),
        "notifications_delivery_methods" to mapOf(
            "zh" to "通知方式",
            "en" to "Delivery",
            "ja" to "Delivery",
            "ko" to "Delivery",
            "es" to "Delivery",
            "fr" to "Delivery",
            "de" to "Delivery",
            "pt" to "Delivery",
            "ru" to "Delivery",
            "ar" to "Delivery"
        ),
        "notifications_info_text" to mapOf(
            "zh" to "奇遇任务将随机不定时推送，每次完成可获得 50-200 积分奖励。您可以在系统设置中配置免打扰时段。",
            "en" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "ja" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "ko" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "es" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "fr" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "de" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "pt" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "ru" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings.",
            "ar" to "Adventure tasks may arrive at random. Completing them earns rewards. You can configure Do Not Disturb in system settings."
        ),
        "notifications_permission_required" to mapOf(
            "zh" to "需要开启系统通知权限后才能接收推送。",
            "en" to "Notification permission is required to receive push alerts.",
            "ja" to "Notification permission is required to receive push alerts.",
            "ko" to "Notification permission is required to receive push alerts.",
            "es" to "Notification permission is required to receive push alerts.",
            "fr" to "Notification permission is required to receive push alerts.",
            "de" to "Notification permission is required to receive push alerts.",
            "pt" to "Notification permission is required to receive push alerts.",
            "ru" to "Notification permission is required to receive push alerts.",
            "ar" to "Notification permission is required to receive push alerts."
        ),
        "notifications_permission_go_settings" to mapOf(
            "zh" to "去设置",
            "en" to "Open Settings",
            "ja" to "Open Settings",
            "ko" to "Open Settings",
            "es" to "Open Settings",
            "fr" to "Open Settings",
            "de" to "Open Settings",
            "pt" to "Open Settings",
            "ru" to "Open Settings",
            "ar" to "Open Settings"
        ),

        // 游戏页面
        "game_btn_market" to mapOf(
            "zh" to "市场",
            "en" to "MARKET",
            "ja" to "市場",
            "ko" to "시장"
        ),
        "game_btn_sail" to mapOf(
            "zh" to "航行",
            "en" to "SAIL",
            "ja" to "航行",
            "ko" to "항해"
        ),
        "game_btn_shipyard" to mapOf(
            "zh" to "船坞",
            "en" to "SHIPYARD",
            "ja" to "造船所",
            "ko" to "조선소"
        ),
        "game_btn_comm" to mapOf(
            "zh" to "通讯",
            "en" to "COMM",
            "ja" to "通信",
            "ko" to "통신"
        ),
        "game_btn_archives" to mapOf(
            "zh" to "档案",
            "en" to "ARCHIVES",
            "ja" to "アーカイブ",
            "ko" to "아카이브"
        ),
        "game_btn_exit" to mapOf(
            "zh" to "退出",
            "en" to "EXIT",
            "ja" to "終了",
            "ko" to "종료"
        ),
        "game_btn_buy" to mapOf(
            "zh" to "购买",
            "en" to "BUY",
            "ja" to "購入",
            "ko" to "구매"
        ),
        "game_btn_sell" to mapOf(
            "zh" to "出售",
            "en" to "SELL",
            "ja" to "売却",
            "ko" to "판매"
        ),
        "game_btn_mint" to mapOf(
            "zh" to "铸造 NFT",
            "en" to "MINT AS NFT",
            "ja" to "NFT鋳造",
            "ko" to "NFT 발행"
        ),
        "game_btn_install" to mapOf(
            "zh" to "安装",
            "en" to "INSTALL",
            "ja" to "インストール",
            "ko" to "설치"
        ),
        "game_btn_retrofit" to mapOf(
            "zh" to "改装",
            "en" to "RETROFIT",
            "ja" to "改造",
            "ko" to "개조"
        ),
        "game_btn_leave" to mapOf(
            "zh" to "离港",
            "en" to "LEAVE DOCK",
            "ja" to "出港",
            "ko" to "출항"
        ),
        "game_btn_close" to mapOf(
            "zh" to "关闭",
            "en" to "CLOSE",
            "ja" to "閉じる",
            "ko" to "닫기"
        ),
        "game_btn_send" to mapOf(
            "zh" to "发送",
            "en" to "SEND",
            "ja" to "送信",
            "ko" to "전송"
        ),
        "game_btn_close_link" to mapOf(
            "zh" to "断开连接",
            "en" to "CLOSE LINK",
            "ja" to "通信終了",
            "ko" to "연결 종료"
        ),
        "game_btn_transmit" to mapOf(
            "zh" to "全部传输",
            "en" to "TRANSMIT ALL",
            "ja" to "全送信",
            "ko" to "모두 전송"
        ),
        "game_btn_jump" to mapOf(
            "zh" to "跳跃至扇区",
            "en" to "JUMP TO SECTOR",
            "ja" to "セクターへジャンプ",
            "ko" to "섹터로 점프"
        ),
        "game_title_market" to mapOf(
            "zh" to "市场",
            "en" to "MARKET",
            "ja" to "市場",
            "ko" to "시장"
        ),
        "game_title_shipyard" to mapOf(
            "zh" to "轨道船坞",
            "en" to "ORBITAL SHIPYARD",
            "ja" to "軌道造船所",
            "ko" to "궤도 조선소"
        ),
        "game_title_nav" to mapOf(
            "zh" to "导航星图",
            "en" to "NAVIGATION CHART",
            "ja" to "航行図",
            "ko" to "항해 차트"
        ),
        "game_title_season" to mapOf(
            "zh" to "赛季情报",
            "en" to "SEASON INTELLIGENCE",
            "ja" to "シーズン情報",
            "ko" to "시즌 정보"
        ),
        "game_title_comm" to mapOf(
            "zh" to "加密频道",
            "en" to "ENCRYPTED CHANNEL",
            "ja" to "暗号化チャンネル",
            "ko" to "암호화 채널"
        ),
        "game_title_archives" to mapOf(
            "zh" to "机密档案",
            "en" to "CLASSIFIED ARCHIVES",
            "ja" to "機密アーカイブ",
            "ko" to "기밀 아카이브"
        ),
        "game_label_port" to mapOf(
            "zh" to "港口",
            "en" to "PORT",
            "ja" to "港",
            "ko" to "항구"
        ),
        "game_label_money" to mapOf(
            "zh" to "G",
            "en" to "G",
            "ja" to "G",
            "ko" to "G"
        ),
        "game_label_cargo" to mapOf(
            "zh" to "货仓",
            "en" to "Cargo Hold",
            "ja" to "貨物室",
            "ko" to "화물칸"
        ),
        "game_label_class" to mapOf(
            "zh" to "船舰等级",
            "en" to "Class Level",
            "ja" to "艦級",
            "ko" to "함선 등급"
        ),
        "game_label_upgrades" to mapOf(
            "zh" to "可用升级",
            "en" to "AVAILABLE UPGRADES",
            "ja" to "利用可能なアップグレード",
            "ko" to "사용 가능한 업그레이드"
        ),
        "game_label_stock" to mapOf(
            "zh" to "库存",
            "en" to "Stock",
            "ja" to "在庫",
            "ko" to "재고"
        ),
        "game_label_you_have" to mapOf(
            "zh" to "持有",
            "en" to "You have",
            "ja" to "所持",
            "ko" to "보유"
        ),
        "game_label_current_loc" to mapOf(
            "zh" to "当前位置",
            "en" to "CURRENT LOCATION",
            "ja" to "現在位置",
            "ko" to "현재 위치"
        ),
        "game_label_travel_cost" to mapOf(
            "zh" to "航行费用",
            "en" to "TRAVEL COST",
            "ja" to "航行費用",
            "ko" to "항해 비용"
        ),
        "game_label_risk" to mapOf(
            "zh" to "风险",
            "en" to "RISK",
            "ja" to "リスク",
            "ko" to "위험"
        ),
        "game_label_cost" to mapOf(
            "zh" to "费用",
            "en" to "COST",
            "ja" to "費用",
            "ko" to "비용"
        ),
        "game_dialog_exit_title" to mapOf(
            "zh" to "退出游戏？",
            "en" to "Exit Game?",
            "ja" to "ゲームを終了しますか？",
            "ko" to "게임을 종료하시겠습니까?"
        ),
        "game_dialog_exit_text" to mapOf(
            "zh" to "确定要结束当前的航海旅程吗？未保存的进度可能会丢失。",
            "en" to "Are you sure you want to end your current voyage? Unsaved progress may be lost.",
            "ja" to "現在の航海を終了してもよろしいですか？保存されていない進行状況は失われる可能性があります。",
            "ko" to "현재 항해를 종료하시겠습니까? 저장되지 않은 진행 상황이 손실될 수 있습니다."
        ),
        "game_log_system_boot" to mapOf(
            "zh" to "> 系统启动中...",
            "en" to "> SYSTEM BOOTING...",
            "ja" to "> システム起動中...",
            "ko" to "> 시스템 부팅 중..."
        ),
        "game_log_connect_net" to mapOf(
            "zh" to "> 连接至星际贸易网络...",
            "en" to "> CONNECTING TO TRADE NETWORK...",
            "ja" to "> 貿易ネットワークに接続中...",
            "ko" to "> 무역 네트워크 연결 중..."
        ),
        "game_log_auth_success" to mapOf(
            "zh" to "> 身份验证成功",
            "en" to "> AUTHENTICATION SUCCESSFUL",
            "ja" to "> 認証成功",
            "ko" to "> 인증 성공"
        ),
        "game_log_player_sync" to mapOf(
            "zh" to "> 玩家数据已同步",
            "en" to "> PLAYER DATA SYNCED",
            "ja" to "> プレイヤーデータ同期完了",
            "ko" to "> 플레이어 데이터 동기화됨"
        ),
        "game_log_current_loc" to mapOf(
            "zh" to "> 当前位置",
            "en" to "> CURRENT LOCATION",
            "ja" to "> 現在位置",
            "ko" to "> 현재 위치"
        ),
        "game_log_balance" to mapOf(
            "zh" to "> 资金余额",
            "en" to "> BALANCE",
            "ja" to "> 残高",
            "ko" to "> 잔액"
        ),
        "game_log_error_data" to mapOf(
            "zh" to "> 错误: 无法获取玩家数据",
            "en" to "> ERROR: FAILED TO FETCH DATA",
            "ja" to "> エラー: データ取得失敗",
            "ko" to "> 오류: 데이터 가져오기 실패"
        ),
        "game_log_season_sync" to mapOf(
            "zh" to "> 同步赛季数据",
            "en" to "> SEASON DATA SYNCED",
            "ja" to "> シーズンデータ同期完了",
            "ko" to "> 시즌 데이터 동기화됨"
        ),
        "game_log_market_event" to mapOf(
            "zh" to "> 市场动态",
            "en" to "> MARKET EVENT",
            "ja" to "> 市場イベント",
            "ko" to "> 시장 이벤트"
        ),
        "game_log_nav_open" to mapOf(
            "zh" to "> 打开导航图...",
            "en" to "> OPENING NAVIGATION CHART...",
            "ja" to "> 航行図を展開中...",
            "ko" to "> 항해 차트 여는 중..."
        ),
        "game_log_sail_start" to mapOf(
            "zh" to "> 正在前往目标港口...",
            "en" to "> SETTING COURSE...",
            "ja" to "> 目標港へ航行中...",
            "ko" to "> 목표 항구로 이동 중..."
        ),
        "game_log_sail_success" to mapOf(
            "zh" to "> 航行成功! 抵达",
            "en" to "> ARRIVED AT",
            "ja" to "> 航行成功! 到着:",
            "ko" to "> 항해 성공! 도착:"
        ),
        "game_log_sail_fail" to mapOf(
            "zh" to "> 航行失败",
            "en" to "> VOYAGE FAILED",
            "ja" to "> 航行失敗",
            "ko" to "> 항해 실패"
        ),
        "game_log_buy_success" to mapOf(
            "zh" to "> 购买成功",
            "en" to "> PURCHASE SUCCESSFUL",
            "ja" to "> 購入成功",
            "ko" to "> 구매 성공"
        ),
        "game_log_buy_fail" to mapOf(
            "zh" to "> 购买失败",
            "en" to "> PURCHASE FAILED",
            "ja" to "> 購入失敗",
            "ko" to "> 구매 실패"
        ),
        "game_log_sell_success" to mapOf(
            "zh" to "> 出售成功",
            "en" to "> SALE SUCCESSFUL",
            "ja" to "> 売却成功",
            "ko" to "> 판매 성공"
        ),
        "game_log_sell_fail" to mapOf(
            "zh" to "> 出售失败",
            "en" to "> SALE FAILED",
            "ja" to "> 売却失敗",
            "ko" to "> 판매 실패"
        ),
        "game_log_mint_success" to mapOf(
            "zh" to "> 铸造成功!",
            "en" to "> MINT SUCCESSFUL!",
            "ja" to "> 鋳造成功!",
            "ko" to "> 발행 성공!"
        ),
        "game_log_mint_fail" to mapOf(
            "zh" to "> 铸造失败",
            "en" to "> MINT FAILED",
            "ja" to "> 鋳造失敗",
            "ko" to "> 발행 실패"
        ),
        "game_log_upgrade_success" to mapOf(
            "zh" to "> 升级成功",
            "en" to "> UPGRADE SUCCESSFUL",
            "ja" to "> アップグレード成功",
            "ko" to "> 업그레이드 성공"
        ),
        "game_log_upgrade_fail" to mapOf(
            "zh" to "> 升级失败",
            "en" to "> UPGRADE FAILED",
            "ja" to "> アップグレード失敗",
            "ko" to "> 업그레이드 실패"
        ),
        "game_log_dungeon_enter" to mapOf(
            "zh" to "> 尝试进入未知扇区...",
            "en" to "> ENTERING UNKNOWN SECTOR...",
            "ja" to "> 未知のセクターへ進入中...",
            "ko" to "> 미지 섹터 진입 중..."
        ),
        "game_log_dungeon_warn" to mapOf(
            "zh" to "> 警告: 进入记忆裂痕!",
            "en" to "> WARNING: MEMORY BREACH!",
            "ja" to "> 警告: メモリーブレイク!",
            "ko" to "> 경고: 기억 균열 진입!"
        ),
        "game_log_dungeon_fail" to mapOf(
            "zh" to "> 进入失败",
            "en" to "> ENTRY FAILED",
            "ja" to "> 進入失敗",
            "ko" to "> 진입 실패"
        ),
        "game_log_dungeon_end" to mapOf(
            "zh" to "> 副本结束",
            "en" to "> DUNGEON ENDED",
            "ja" to "> ダンジョン終了",
            "ko" to "> 던전 종료"
        ),
        "game_log_action_fail" to mapOf(
            "zh" to "> 行动失败",
            "en" to "> ACTION FAILED",
            "ja" to "> アクション失敗",
            "ko" to "> 행동 실패"
        ),
        "game_log_contribute_success" to mapOf(
            "zh" to "> 提交成功!",
            "en" to "> SUBMISSION SUCCESSFUL!",
            "ja" to "> 提出成功!",
            "ko" to "> 제출 성공!"
        ),
        "game_log_contribute_fail" to mapOf(
            "zh" to "> 提交失败",
            "en" to "> SUBMISSION FAILED",
            "ja" to "> 提出失敗",
            "ko" to "> 제출 실패"
        ),
        "game_label_vessel_status" to mapOf(
            "zh" to "当前船舰状态",
            "en" to "CURRENT VESSEL STATUS",
            "ja" to "現在の船の状態",
            "ko" to "현재 선박 상태"
        ),
        "game_desc_cargo_upgrade" to mapOf(
            "zh" to "增加贸易载货量",
            "en" to "Increases trading capacity.",
            "ja" to "貿易能力を向上させる",
            "ko" to "무역 용량 증가"
        ),
        "game_desc_ship_upgrade" to mapOf(
            "zh" to "提升船体完整性与系统效率",
            "en" to "Enhances hull integrity and system efficiency.",
            "ja" to "船体の完全性とシステム効率を向上させる",
            "ko" to "선체 무결성 및 시스템 효율성 향상"
        ),
        "game_intro_1" to mapOf(
            "zh" to "系统启动程序已初始化...",
            "en" to "SYSTEM_BOOT_SEQUENCE_INITIATED...",
            "ja" to "システム起動シーケンス開始...",
            "ko" to "시스템 부팅 시퀀스 시작됨..."
        ),
        "game_intro_2" to mapOf(
            "zh" to "正在加载核心记忆模块...",
            "en" to "LOADING_CORE_MEMORY_MODULES...",
            "ja" to "コアメモリモジュールを読み込み中...",
            "ko" to "핵심 메모리 모듈 로딩 중..."
        ),
        "game_intro_3" to mapOf(
            "zh" to "正在连接至创世信号...",
            "en" to "CONNECTING_TO_GENESIS_SIGNAL...",
            "ja" to "ジェネシスシグナルに接続中...",
            "ko" to "제네시스 신호에 연결 중..."
        ),
        "game_intro_4" to mapOf(
            "zh" to "欢迎来到赛季:",
            "en" to "WELCOME_TO_SEASON:",
            "ja" to "シーズンへようこそ:",
            "ko" to "시즌에 오신 것을 환영합니다:"
        ),
        "game_intro_5" to mapOf(
            "zh" to "准备进入沉浸模式.",
            "en" to "PREPARE_FOR_IMMERSION.",
            "ja" to "没入の準備をしてください.",
            "ko" to "몰입 준비."
        ),

        // 关于页面
        "about_intro" to mapOf(
            "zh" to "Soulon是一款为您构建长期记忆的次时代应用，通过建立您的记忆档案和人格画像，提供个性化的服务，为跨智能体网路提供安全隐私的桥梁，",
            "en" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "ja" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "ko" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "es" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "fr" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "de" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "pt" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "ru" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks.",
            "ar" to "Soulon is a next-generation long-term memory app. It builds your memory profile and persona to deliver personalized services and a secure, privacy-preserving bridge across agent networks."
        ),
        "about_version_name_label" to mapOf(
            "zh" to "版本号",
            "en" to "Version",
            "ja" to "Version",
            "ko" to "Version",
            "es" to "Version",
            "fr" to "Version",
            "de" to "Version",
            "pt" to "Version",
            "ru" to "Version",
            "ar" to "Version"
        ),
        "about_app_intro_title" to mapOf(
            "zh" to "应用简介",
            "en" to "About",
            "ja" to "About",
            "ko" to "About",
            "es" to "About",
            "fr" to "About",
            "de" to "About",
            "pt" to "About",
            "ru" to "About",
            "ar" to "About"
        ),
        "staking_coming_soon_toast" to mapOf(
            "zh" to "生态质押功能正在快速准备中，敬请期待",
            "en" to "Eco staking is under preparation. Coming soon.",
            "ja" to "Eco staking is under preparation. Coming soon.",
            "ko" to "Eco staking is under preparation. Coming soon.",
            "es" to "Eco staking is under preparation. Coming soon.",
            "fr" to "Eco staking is under preparation. Coming soon.",
            "de" to "Eco staking is under preparation. Coming soon.",
            "pt" to "Eco staking is under preparation. Coming soon.",
            "ru" to "Eco staking is under preparation. Coming soon.",
            "ar" to "Eco staking is under preparation. Coming soon."
        ),
        
        // 语言设置
        "language_settings" to mapOf(
            "zh" to "语言设置",
            "en" to "Language Settings",
            "ja" to "言語設定",
            "ko" to "언어 설정",
            "es" to "Configuración de idioma",
            "fr" to "Paramètres de langue",
            "de" to "Spracheinstellungen",
            "pt" to "Configurações de idioma",
            "ru" to "Настройки языка",
            "ar" to "إعدادات اللغة"
        ),
        "language_current" to mapOf(
            "zh" to "当前语言",
            "en" to "Current Language",
            "ja" to "現在の言語",
            "ko" to "현재 언어",
            "es" to "Idioma actual",
            "fr" to "Langue actuelle",
            "de" to "Aktuelle Sprache",
            "pt" to "Idioma atual",
            "ru" to "Текущий язык",
            "ar" to "اللغة الحالية"
        ),
        "language_change" to mapOf(
            "zh" to "切换语言",
            "en" to "Change Language",
            "ja" to "言語を変更",
            "ko" to "언어 변경",
            "es" to "Cambiar idioma",
            "fr" to "Changer de langue",
            "de" to "Sprache ändern",
            "pt" to "Alterar idioma",
            "ru" to "Сменить язык",
            "ar" to "تغيير اللغة"
        ),
        "language_change_confirm" to mapOf(
            "zh" to "确定要切换语言吗？应用将重新加载。",
            "en" to "Change language? The app will reload.",
            "ja" to "言語を変更しますか？アプリが再読み込みされます。",
            "ko" to "언어를 변경하시겠습니까? 앱이 다시 로드됩니다.",
            "es" to "¿Cambiar idioma? La aplicación se recargará.",
            "fr" to "Changer de langue ? L'application se rechargera.",
            "de" to "Sprache ändern? Die App wird neu geladen.",
            "pt" to "Alterar idioma? O aplicativo será recarregado.",
            "ru" to "Сменить язык? Приложение перезагрузится.",
            "ar" to "تغيير اللغة؟ سيتم إعادة تحميل التطبيق."
        ),
        
        // 设置页面
        "settings_language" to mapOf(
            "zh" to "语言",
            "en" to "Language",
            "ja" to "言語",
            "ko" to "언어",
            "es" to "Idioma",
            "fr" to "Langue",
            "de" to "Sprache",
            "pt" to "Idioma",
            "ru" to "Язык",
            "ar" to "اللغة"
        ),
        "settings_theme" to mapOf(
            "zh" to "主题",
            "en" to "Theme",
            "ja" to "テーマ",
            "ko" to "테마",
            "es" to "Tema",
            "fr" to "Thème",
            "de" to "Thema",
            "pt" to "Tema",
            "ru" to "Тема",
            "ar" to "السمة"
        ),
        "settings_clear_cache" to mapOf(
            "zh" to "清除缓存",
            "en" to "Clear Cache",
            "ja" to "キャッシュをクリア",
            "ko" to "캐시 지우기",
            "es" to "Borrar caché",
            "fr" to "Vider le cache",
            "de" to "Cache leeren",
            "pt" to "Limpar cache",
            "ru" to "Очистить кэш",
            "ar" to "مسح ذاكرة التخزين المؤقت"
        ),
        "settings_privacy_policy" to mapOf(
            "zh" to "隐私政策",
            "en" to "Privacy Policy",
            "ja" to "プライバシーポリシー",
            "ko" to "개인정보 처리방침",
            "es" to "Política de privacidad",
            "fr" to "Politique de confidentialité",
            "de" to "Datenschutzrichtlinie",
            "pt" to "Política de privacidade",
            "ru" to "Политика конфиденциальности",
            "ar" to "سياسة الخصوصية"
        ),
        "settings_terms" to mapOf(
            "zh" to "用户协议",
            "en" to "Terms of Service",
            "ja" to "利用規約",
            "ko" to "서비스 약관",
            "es" to "Términos de servicio",
            "fr" to "Conditions d'utilisation",
            "de" to "Nutzungsbedingungen",
            "pt" to "Termos de serviço",
            "ru" to "Условия использования",
            "ar" to "شروط الخدمة"
        ),
        
        // 错误消息
        "error_network" to mapOf(
            "zh" to "网络连接失败",
            "en" to "Network Connection Failed",
            "ja" to "ネットワーク接続に失敗しました",
            "ko" to "네트워크 연결 실패",
            "es" to "Error de conexión de red",
            "fr" to "Échec de connexion réseau",
            "de" to "Netzwerkverbindung fehlgeschlagen",
            "pt" to "Falha na conexão de rede",
            "ru" to "Ошибка сетевого подключения",
            "ar" to "فشل الاتصال بالشبكة"
        ),
        "error_network_desc" to mapOf(
            "zh" to "请检查网络连接后重试",
            "en" to "Please check your network connection and try again",
            "ja" to "ネットワーク接続を確認して再試行してください",
            "ko" to "네트워크 연결을 확인하고 다시 시도해 주세요",
            "es" to "Por favor verifica tu conexión de red e intenta de nuevo",
            "fr" to "Veuillez vérifier votre connexion réseau et réessayer",
            "de" to "Bitte überprüfen Sie Ihre Netzwerkverbindung und versuchen Sie es erneut",
            "pt" to "Por favor, verifique sua conexão de rede e tente novamente",
            "ru" to "Пожалуйста, проверьте сетевое подключение и попробуйте снова",
            "ar" to "يرجى التحقق من اتصالك بالشبكة والمحاولة مرة أخرى"
        ),
        "error_cancelled" to mapOf(
            "zh" to "操作已取消",
            "en" to "Operation Cancelled",
            "ja" to "操作がキャンセルされました",
            "ko" to "작업이 취소되었습니다",
            "es" to "Operación cancelada",
            "fr" to "Opération annulée",
            "de" to "Vorgang abgebrochen",
            "pt" to "Operação cancelada",
            "ru" to "Операция отменена",
            "ar" to "تم إلغاء العملية"
        ),
        "error_cancelled_desc" to mapOf(
            "zh" to "您已取消此操作，可以随时重试",
            "en" to "You have cancelled this operation, you can retry anytime",
            "ja" to "この操作をキャンセルしました。いつでも再試行できます",
            "ko" to "이 작업을 취소했습니다. 언제든지 다시 시도할 수 있습니다",
            "es" to "Has cancelado esta operación, puedes reintentar en cualquier momento",
            "fr" to "Vous avez annulé cette opération, vous pouvez réessayer à tout moment",
            "de" to "Sie haben diesen Vorgang abgebrochen, Sie können jederzeit erneut versuchen",
            "pt" to "Você cancelou esta operação, pode tentar novamente a qualquer momento",
            "ru" to "Вы отменили эту операцию, вы можете повторить попытку в любое время",
            "ar" to "لقد ألغيت هذه العملية، يمكنك إعادة المحاولة في أي وقت"
        ),
        "error_unknown" to mapOf(
            "zh" to "操作失败",
            "en" to "Operation Failed",
            "ja" to "操作に失敗しました",
            "ko" to "작업 실패",
            "es" to "Operación fallida",
            "fr" to "Opération échouée",
            "de" to "Vorgang fehlgeschlagen",
            "pt" to "Operação falhou",
            "ru" to "Операция не удалась",
            "ar" to "فشلت العملية"
        ),
        "error_unknown_desc" to mapOf(
            "zh" to "出现了一些问题，请稍后重试",
            "en" to "Something went wrong, please try again later",
            "ja" to "問題が発生しました。後でもう一度お試しください",
            "ko" to "문제가 발생했습니다. 나중에 다시 시도해 주세요",
            "es" to "Algo salió mal, por favor intenta de nuevo más tarde",
            "fr" to "Quelque chose s'est mal passé, veuillez réessayer plus tard",
            "de" to "Etwas ist schief gelaufen, bitte versuchen Sie es später erneut",
            "pt" to "Algo deu errado, por favor tente novamente mais tarde",
            "ru" to "Что-то пошло не так, пожалуйста, попробуйте позже",
            "ar" to "حدث خطأ ما، يرجى المحاولة مرة أخرى لاحقاً"
        )
    )

    internal fun translationsForTest(): Map<String, Map<String, String>> = translations
}

/**
 * Composable 字符串获取函数
 * 
 * 在 Composable 中使用，会自动响应语言变化
 */
@Composable
fun rememberStrings(): AppStrings {
    // 这里可以添加语言变化的监听
    return AppStrings
}
