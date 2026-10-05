package com.sengine.engine.localization

import org.json.JSONObject
import java.io.File

/**
 * Localization system for multi-language support.
 * Manages translation strings and language switching.
 */
class LocalizationSystem {
    private val translations = mutableMapOf<String, MutableMap<String, String>>()
    private var currentLanguage = "en"
    private var fallbackLanguage = "en"

    /**
     * Load translations from a JSON file
     */
    fun loadFromFile(file: File) {
        if (!file.exists()) return

        try {
            val json = JSONObject(file.readText())
            val language = file.nameWithoutExtension

            val langMap = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                langMap[key] = json.getString(key)
            }

            translations[language] = langMap
        } catch (e: Exception) {
            println("Failed to load localization file: ${e.message}")
        }
    }

    /**
     * Load translations from JSON string
     */
    fun loadFromJson(language: String, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val langMap = mutableMapOf<String, String>()

            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                langMap[key] = json.getString(key)
            }

            translations[language] = langMap
        } catch (e: Exception) {
            println("Failed to load localization: ${e.message}")
        }
    }

    /**
     * Get a translated string
     */
    fun getText(key: String, vararg args: Any): String {
        // Try current language
        var text = translations[currentLanguage]?.get(key)

        // Fallback to default language
        if (text == null) {
            text = translations[fallbackLanguage]?.get(key)
        }

        // Return key if not found
        if (text == null) {
            return key
        }

        // Format with arguments
        return if (args.isNotEmpty()) {
            try {
                String.format(text, *args)
            } catch (e: Exception) {
                text
            }
        } else {
            text
        }
    }

    /**
     * Set current language
     */
    fun setLanguage(language: String) {
        if (translations.containsKey(language)) {
            currentLanguage = language
        }
    }

    /**
     * Get current language
     */
    fun getCurrentLanguage(): String = currentLanguage

    /**
     * Get all available languages
     */
    fun getAvailableLanguages(): Set<String> = translations.keys

    /**
     * Check if a translation exists
     */
    fun hasTranslation(key: String, language: String = currentLanguage): Boolean {
        return translations[language]?.containsKey(key) == true
    }

    /**
     * Add or update a translation
     */
    fun setTranslation(language: String, key: String, value: String) {
        if (!translations.containsKey(language)) {
            translations[language] = mutableMapOf()
        }
        translations[language]?.put(key, value)
    }

    /**
     * Get all translations for a language
     */
    fun getTranslations(language: String): Map<String, String> {
        return translations[language] ?: emptyMap()
    }

    /**
     * Export translations to JSON
     */
    fun exportToJson(language: String): String {
        val json = JSONObject()
        translations[language]?.forEach { (key, value) ->
            json.put(key, value)
        }
        return json.toString(2)
    }

    /**
     * Save translations to file
     */
    fun saveToFile(language: String, file: File) {
        file.writeText(exportToJson(language))
    }

    /**
     * Clear all translations
     */
    fun clear() {
        translations.clear()
    }

    /**
     * Get translation count for a language
     */
    fun getTranslationCount(language: String = currentLanguage): Int {
        return translations[language]?.size ?: 0
    }
}

/**
 * Helper object for quick access to localization
 */
object L10n {
    private var system: LocalizationSystem? = null

    fun init(localizationSystem: LocalizationSystem) {
        system = localizationSystem
    }

    fun t(key: String, vararg args: Any): String {
        return system?.getText(key, *args) ?: key
    }

    fun setLanguage(language: String) {
        system?.setLanguage(language)
    }

    fun getCurrentLanguage(): String {
        return system?.getCurrentLanguage() ?: "en"
    }
}
