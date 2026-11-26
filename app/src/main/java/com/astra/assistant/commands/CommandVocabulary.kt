package com.astra.assistant.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.astra.assistant.voice.OfflineRecognizer
import java.util.Locale

// Builds the word lists that make the offline recognizer good at ASTRA's own commands.
object CommandVocabulary {

    @Volatile
    var appLabels: List<String> = emptyList()
        private set

    private const val MAX_WORDS = 1200

    private val EN_WAKE = listOf("astra", "astro", "extra", "astral", "aster", "a star", "hey", "ok", "okay", "hi", "hello")
    private val HI_WAKE = listOf("अस्त्र", "एस्ट्रा", "अस्ट्रा", "हे", "ओके")

    private val EN_STOP = listOf(
        "stop", "quiet", "silence", "enough", "cancel", "speaking", "talking", "shut", "up", "be", "voice",
        "bus", "bass", "bas", "chup", "chop", "karo", "carol", "kara", "ho", "jao", "ruk", "bolna", "band",
        "awaaz", "awaz"
    )
    private val HI_STOP = listOf(
        "बस", "करो", "चुप", "रुको", "रुक", "जाओ", "हो", "बंद", "आवाज", "बोलना", "स्टॉप", "शांत"
    )

    private val EN_FIXED = (
        "open launch start close stop pause resume play next previous skip song songs music track " +
            "volume up down increase decrease lower raise louder quieter softer brightness mute unmute turn on off " +
            "set alarm for at am pm a the to my me please can you could would i want listen watch something some " +
            "call text message send whatsapp go back home recent apps app notifications screenshot take flashlight " +
            "torch bluetooth wifi wi fi airplane flight mode data mobile hotspot location what time is it search " +
            "by percent little bit lot much maximum minimum and then also yes no yeah yep okay ok sure confirm " +
            "cancel do go ahead hello hi hey youtube chrome camera maps settings clock calculator gallery photos " +
            "phone contacts messages instagram facebook telegram spotify gmail drive video videos ring ringer media " +
            "this that who how why where when explain tell about search wake up me zero one two three four five " +
            "six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen " +
            "twenty thirty forty fifty sixty seventy eighty ninety hundred"
        ).split(" ")

    fun build(context: Context, hindi: Boolean): OfflineRecognizer.Vocabulary {
        val apps = loadAppLabels(context)
        appLabels = apps
        if (hindi) {
            return OfflineRecognizer.Vocabulary(HI_WAKE, HI_STOP, emptyList())
        }
        val words = LinkedHashSet<String>()
        words.addAll(EN_FIXED)
        words.addAll(EN_WAKE)
        for (label in apps) {
            for (w in label.split(" ")) {
                val letters = w.filter { it.isLetter() }
                if (letters.length in 2..20) words.add(letters)
            }
        }
        words.addAll(contactFirstNames(context))
        return OfflineRecognizer.Vocabulary(EN_WAKE, EN_STOP, words.take(MAX_WORDS))
    }

    @Suppress("DEPRECATION")
    private fun loadAppLabels(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString().lowercase(Locale.ROOT).trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun contactFirstNames(context: Context): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val names = LinkedHashSet<String>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            ) ?: return emptyList()
            cursor.use {
                while (it.moveToNext() && names.size < 300) {
                    val full = it.getString(0) ?: continue
                    val first = full.trim().split(" ").firstOrNull().orEmpty().lowercase(Locale.ROOT)
                    val letters = first.filter { c -> c.isLetter() }
                    if (letters.length in 2..20) names.add(letters)
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return names.toList()
    }
}
