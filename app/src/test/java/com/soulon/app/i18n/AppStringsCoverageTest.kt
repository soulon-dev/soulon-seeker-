package com.soulon.app.i18n

import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsCoverageTest {
    @Test
    fun allTranslationsCoverSupportedLanguages() {
        val supported = LocaleManager.SUPPORTED_LANGUAGES.map { it.code }.distinct()
        val translations = AppStrings.translationsForTest()

        val missing = mutableListOf<String>()
        for ((key, byLang) in translations) {
            for (lang in supported) {
                if (!byLang.containsKey(lang)) {
                    missing.add("$key:$lang")
                }
            }
        }

        assertTrue("Missing translations: ${missing.take(50)}", missing.isEmpty())
    }

    @Test
    fun formatSpecifiersMatchAcrossLanguages() {
        val supported = LocaleManager.SUPPORTED_LANGUAGES.map { it.code }.distinct()
        val translations = AppStrings.translationsForTest()

        val mismatches = mutableListOf<String>()
        for ((key, byLang) in translations) {
            val en = byLang["en"] ?: continue
            val enSpecs = formatSpecs(en)
            if (enSpecs.isEmpty()) continue

            for (lang in supported) {
                val v = byLang[lang] ?: continue
                val specs = formatSpecs(v)
                if (specs != enSpecs) {
                    mismatches.add("$key:$lang")
                }
            }
        }

        assertTrue("Format specifier mismatches: ${mismatches.take(50)}", mismatches.isEmpty())
    }

    private fun formatSpecs(s: String): List<String> {
        val regex = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]")
        return regex.findAll(s).map { it.value }.toList()
    }
}

