package com.straylabs.hound.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class TransferType { UPLOAD, DOWNLOAD }
enum class TransferStatus { COMPLETED, FAILED, CANCELLED }

data class TransferRecord(
    val id: Long = System.currentTimeMillis(),
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val type: TransferType,
    val status: TransferStatus = TransferStatus.COMPLETED,
    val timestamp: Long = System.currentTimeMillis(),
    val isServer: Boolean
)

data class TransferSession(
    val id: Long,
    val startTime: Long,
    val records: List<TransferRecord>
)

class TransferHistory(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentSessionId: Long = 0L

    fun startNewSession() {
        currentSessionId = System.currentTimeMillis()
    }

    fun addRecord(record: TransferRecord) {
        val sessions = getSessions().toMutableList()
        
        // Find current session or create new one
        val sessionIndex = sessions.indexOfFirst { it.id == currentSessionId }
        val currentRecords = if (sessionIndex >= 0) {
            sessions[sessionIndex].records.toMutableList()
        } else {
            mutableListOf()
        }
        
        currentRecords.add(0, record)
        
        val updatedSession = TransferSession(
            id = currentSessionId,
            startTime = if (sessionIndex >= 0) sessions[sessionIndex].startTime else currentSessionId,
            records = currentRecords.take(MAX_RECORDS_PER_SESSION)
        )
        
        if (sessionIndex >= 0) {
            sessions[sessionIndex] = updatedSession
        } else {
            sessions.add(0, updatedSession)
        }
        
        // Keep only last 2 sessions
        val trimmedSessions = sessions.take(MAX_SESSIONS)
        saveSessions(trimmedSessions)
    }

    fun getSessions(): List<TransferSession> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val sessionObj = array.getJSONObject(i)
                val recordsArray = sessionObj.getJSONArray("records")
                val records = (0 until recordsArray.length()).map { j ->
                    val obj = recordsArray.getJSONObject(j)
                    TransferRecord(
                        id = obj.getLong("id"),
                        fileName = obj.getString("fileName"),
                        filePath = obj.getString("filePath"),
                        fileSize = obj.getLong("fileSize"),
                        type = TransferType.valueOf(obj.getString("type")),
                        status = TransferStatus.valueOf(obj.getString("status")),
                        timestamp = obj.getLong("timestamp"),
                        isServer = obj.getBoolean("isServer")
                    )
                }
                TransferSession(
                    id = sessionObj.getLong("id"),
                    startTime = sessionObj.getLong("startTime"),
                    records = records
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCurrentSessionRecords(): List<TransferRecord> {
        return getSessions().find { it.id == currentSessionId }?.records ?: emptyList()
    }

    fun clearCurrentSession() {
        val sessions = getSessions().filter { it.id != currentSessionId }
        saveSessions(sessions)
    }

    fun clearAllHistory() {
        prefs.edit().remove(KEY_SESSIONS).apply()
    }

    private fun saveSessions(sessions: List<TransferSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            val recordsArray = JSONArray()
            session.records.forEach { rec ->
                recordsArray.put(JSONObject().apply {
                    put("id", rec.id)
                    put("fileName", rec.fileName)
                    put("filePath", rec.filePath)
                    put("fileSize", rec.fileSize)
                    put("type", rec.type.name)
                    put("status", rec.status.name)
                    put("timestamp", rec.timestamp)
                    put("isServer", rec.isServer)
                })
            }
            array.put(JSONObject().apply {
                put("id", session.id)
                put("startTime", session.startTime)
                put("records", recordsArray)
            })
        }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "transfer_history"
        private const val KEY_SESSIONS = "transfer_sessions"
        private const val MAX_SESSIONS = 2
        private const val MAX_RECORDS_PER_SESSION = 50
    }
}
