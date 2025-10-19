package com.example.bond

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ConcurrentHashMap

class TimeTracker {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // 🔹 IN-MEMORY CACHE: Track last update time per user
    private val lastUpdateTime = ConcurrentHashMap<String, Long>()

    companion object {
        private const val TAG = "TimeTracker"
        private const val UPDATE_INTERVAL_MS = 60_000L // 1 minute
    }

    fun updateTimeTracking(otherUsername: String, callback: ((Result<String>) -> Unit)? = null) {
        Log.d(TAG, "🔔 updateTimeTracking() called for: $otherUsername")

        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.e(TAG, "❌ ERROR: User not logged in")
            callback?.invoke(Result.failure(Exception("Not logged in")))
            return
        }

        val myUsername = currentUser.email?.substringBefore("@") ?: run {
            Log.e(TAG, "❌ ERROR: Could not get username from email")
            callback?.invoke(Result.failure(Exception("Could not get username")))
            return
        }

        Log.d(TAG, "✅ Current user: $myUsername")

        // 🔹 CHECK IN-MEMORY CACHE FIRST
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTime[otherUsername]

        Log.d(TAG, "⏰ Current time: $now")
        Log.d(TAG, "⏰ Last update for $otherUsername: $lastUpdate")

        if (lastUpdate != null) {
            val timeSinceLastUpdate = now - lastUpdate
            val secondsSince = timeSinceLastUpdate / 1000
            Log.d(TAG, "⏰ Time since last update: ${timeSinceLastUpdate}ms ($secondsSince seconds)")

            if (timeSinceLastUpdate < UPDATE_INTERVAL_MS) {
                val secondsRemaining = (UPDATE_INTERVAL_MS - timeSinceLastUpdate) / 1000
                Log.d(TAG, "⏳ SKIPPED ($otherUsername): Too soon. Wait $secondsRemaining more seconds")
                callback?.invoke(Result.success("Skipped - wait $secondsRemaining seconds"))
                return
            } else {
                Log.d(TAG, "✅ Enough time passed ($secondsSince seconds >= 60), proceeding with update")
            }
        } else {
            Log.d(TAG, "✅ First time tracking this user, proceeding with update")
        }

        // 🔥 FIX: UPDATE CACHE IMMEDIATELY (before database call)
        // This prevents race conditions where multiple calls slip through
        lastUpdateTime[otherUsername] = now
        Log.d(TAG, "🔒 Cache locked for $otherUsername at $now")

        Log.d(TAG, "========== START TIME TRACKING ==========")

        // Determine alphabetical order
        val id1 = if (myUsername < otherUsername) myUsername else otherUsername
        val id2 = if (myUsername < otherUsername) otherUsername else myUsername

        Log.d(TAG, "🔍 Looking for pair: ID1=$id1, ID2=$id2")

        // Query the "pairs" collection
        db.collection("pairs")
            .whereEqualTo("ID1", id1)
            .whereEqualTo("ID2", id2)
            .get()
            .addOnSuccessListener { querySnapshot ->
                Log.d(TAG, "📥 Query completed. Empty: ${querySnapshot.isEmpty}")

                if (querySnapshot.isEmpty) {
                    Log.e(TAG, "❌ ERROR: No pair found between $id1 and $id2")
                    callback?.invoke(Result.failure(Exception("No pair found")))
                    return@addOnSuccessListener
                }

                val pairDoc = querySnapshot.documents.first()
                Log.d(TAG, "✅ Found pair document ID: ${pairDoc.id}")

                // Check if BOTH users have bonded
                val bonded1 = pairDoc.getBoolean("Bonded1") ?: false
                val bonded2 = pairDoc.getBoolean("Bonded2") ?: false

                Log.d(TAG, "🔗 Bonding status: Bonded1=$bonded1, Bonded2=$bonded2")

                if (!bonded1 || !bonded2) {
                    Log.w(TAG, "⚠️ SKIPPED: Users are not mutually bonded")
                    // 🔥 ROLLBACK: Remove cache entry since we didn't actually update
                    lastUpdateTime.remove(otherUsername)
                    callback?.invoke(Result.failure(Exception("Users must both accept the bond")))
                    return@addOnSuccessListener
                }

                Log.d(TAG, "✅✅ Users are mutually bonded! Proceeding with time update...")

                val totalTimeMinutes = pairDoc.getLong("totalTimeMinutes") ?: 0L

                Log.d(TAG, "📊 Current totalTimeMinutes: $totalTimeMinutes")

                // Update database
                val newTotalTime = totalTimeMinutes + 1
                val updates = hashMapOf<String, Any>(
                    "totalTimeMinutes" to newTotalTime,
                    "lastSeen" to FieldValue.serverTimestamp()
                )

                Log.d(TAG, "🔄 Updating database: $totalTimeMinutes → $newTotalTime")

                pairDoc.reference.update(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅✅✅ SUCCESS: Database updated!")
                        Log.d(TAG, "   New total time: $newTotalTime minutes")
                        Log.d(TAG, "========== END TIME TRACKING (SUCCESS) ==========\n")
                        callback?.invoke(Result.success("Time updated! Total: $newTotalTime minutes"))
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌❌ ERROR: Failed to update database: ${e.message}")
                        // 🔥 ROLLBACK: Remove cache entry on failure
                        lastUpdateTime.remove(otherUsername)
                        Log.e(TAG, "========== END TIME TRACKING (ERROR) ==========\n")
                        callback?.invoke(Result.failure(e))
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌❌ ERROR: Query failed: ${e.message}")
                // 🔥 ROLLBACK: Remove cache entry on failure
                lastUpdateTime.remove(otherUsername)
                Log.e(TAG, "========== END TIME TRACKING (ERROR) ==========\n")
                callback?.invoke(Result.failure(e))
            }
    }

    fun clearCache() {
        lastUpdateTime.clear()
        Log.d(TAG, "Cache cleared")
    }
}