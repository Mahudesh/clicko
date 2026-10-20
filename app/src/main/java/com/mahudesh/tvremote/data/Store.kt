package com.mahudesh.tvremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A TV we've paired with. Identified by its certificate, so IP changes don't matter. */
data class SavedTv(
    val fingerprint: String,
    val name: String,
    val lastHost: String,
    val adbEnabled: Boolean = false,
    /** This TV only pairs/connects over TLS 1.2 (learned during pairing). */
    val tls12: Boolean = false,
    /** 2 = protobuf protocol (newer TVs), 1 = legacy JSON/binary (Remote Service < 5). */
    val protocol: Int = 2,
    /** ADB port for Pro mode: 5555 classic, or the Wireless-debugging connect port. */
    val adbPort: Int = 5555,
)

sealed interface MacroStep {
    data class Key(val code: Int) : MacroStep
    data class Link(val url: String) : MacroStep
    data class Text(val text: String) : MacroStep
    data class Wait(val millis: Long) : MacroStep
}

data class Macro(val id: String, val name: String, val emoji: String, val steps: List<MacroStep>)

private val Context.dataStore by preferencesDataStore("tv_remote")

class Store(private val context: Context) {
    private val tvsKey = stringPreferencesKey("tvs")
    private val activeKey = stringPreferencesKey("active_tv")
    private val macrosKey = stringPreferencesKey("macros")
    private val hapticsKey = booleanPreferencesKey("haptics")

    val haptics: Flow<Boolean> = context.dataStore.data.map { it[hapticsKey] ?: true }
    suspend fun setHaptics(on: Boolean) = context.dataStore.edit { it[hapticsKey] = on }

    val tvs: Flow<List<SavedTv>> = context.dataStore.data.map { decodeTvs(it[tvsKey]) }
    val activeFingerprint: Flow<String?> = context.dataStore.data.map { it[activeKey] }
    val macros: Flow<List<Macro>> = context.dataStore.data.map { p -> p[macrosKey]?.let(::decodeMacros) ?: DefaultMacros }

    suspend fun activeTv(): SavedTv? {
        val prefs = context.dataStore.data.first()
        val list = decodeTvs(prefs[tvsKey])
        return list.firstOrNull { it.fingerprint == prefs[activeKey] } ?: list.firstOrNull()
    }

    suspend fun upsert(tv: SavedTv, makeActive: Boolean = true) = context.dataStore.edit { p ->
        val list = decodeTvs(p[tvsKey]).filterNot { it.fingerprint == tv.fingerprint } + tv
        p[tvsKey] = encodeTvs(list)
        if (makeActive) p[activeKey] = tv.fingerprint
    }

    suspend fun remove(fingerprint: String) = context.dataStore.edit { p ->
        p[tvsKey] = encodeTvs(decodeTvs(p[tvsKey]).filterNot { it.fingerprint == fingerprint })
        if (p[activeKey] == fingerprint) p.remove(activeKey)
    }

    suspend fun setActive(fingerprint: String) = context.dataStore.edit { it[activeKey] = fingerprint }

    suspend fun saveMacros(list: List<Macro>) = context.dataStore.edit { it[macrosKey] = encodeMacros(list) }

    private fun encodeTvs(list: List<SavedTv>) = JSONArray(list.map {
        JSONObject().put("fp", it.fingerprint).put("name", it.name).put("host", it.lastHost).put("adb", it.adbEnabled).put("tls12", it.tls12).put("protocol", it.protocol).put("adbPort", it.adbPort)
    }).toString()

    private fun decodeTvs(raw: String?): List<SavedTv> = runCatching {
        val a = JSONArray(raw ?: return emptyList())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).run { SavedTv(getString("fp"), getString("name"), getString("host"), optBoolean("adb"), optBoolean("tls12"), optInt("protocol", 2), optInt("adbPort", 5555)) }
        }
    }.getOrDefault(emptyList())

    private fun encodeMacros(list: List<Macro>) = JSONArray(list.map { m ->
        JSONObject().put("id", m.id).put("name", m.name).put("emoji", m.emoji).put("steps", JSONArray(m.steps.map { s ->
            when (s) {
                is MacroStep.Key -> JSONObject().put("t", "key").put("v", s.code)
                is MacroStep.Link -> JSONObject().put("t", "link").put("v", s.url)
                is MacroStep.Text -> JSONObject().put("t", "text").put("v", s.text)
                is MacroStep.Wait -> JSONObject().put("t", "wait").put("v", s.millis)
            }
        }))
    }).toString()

    private fun decodeMacros(raw: String): List<Macro> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val steps = o.getJSONArray("steps")
            Macro(o.getString("id"), o.getString("name"), o.optString("emoji", "✨"), (0 until steps.length()).map { j ->
                val s = steps.getJSONObject(j)
                when (s.getString("t")) {
                    "key" -> MacroStep.Key(s.getInt("v"))
                    "link" -> MacroStep.Link(s.getString("v"))
                    "text" -> MacroStep.Text(s.getString("v"))
                    else -> MacroStep.Wait(s.getLong("v"))
                }
            })
        }
    }.getOrDefault(DefaultMacros)
}
