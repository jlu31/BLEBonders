package com.example.bond.data

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import java.util.concurrent.ConcurrentHashMap

data class PairData(
    val id: String = "",
    val ID1: String = "",
    val ID2: String = "",
    val bonded1: Boolean = false,
    val bonded2: Boolean = false,
    val sim_score: Double = 0.0,
    val time: Timestamp? = null
)
data class UserData(
    val email: String = "",
    val username: String = "",
    val incoming: Set<String> = emptySet(),
    val bonded: Set<String> = emptySet()
)

data class BondedUserWithTime(
    val username: String = "",
    val email: String = "",
    val totalTimeMinutes: Long = 0L
)

object FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // In-memory lock to prevent concurrent duplicate queries for same pair
    private val activeLookups = ConcurrentHashMap.newKeySet<String>()

    /**
     * Find or create a Firestore pair document safely (no duplicates).
     * This function should be called from a background thread.
     */
    fun getOrCreatePair(otherID: String, simScore: Double = 0.0): PairData? {
        val currentUser = auth.currentUser ?: return null
        val myID = currentUser.email?.substringBefore("@") ?: return null

        // Sort IDs alphabetically
        val (id1, id2) = if (myID < otherID) myID to otherID else otherID to myID
        val key = "$id1|$id2"

        // Prevent duplicate concurrent calls
        if (!activeLookups.add(key)) {
            Log.w("FirestoreRepository", "Skipping duplicate lookup for $key (still in progress)")
            return null
        }

        try {
            Log.d("FirestoreRepository", "Searching pair for $id1 ↔ $id2")

            val query: QuerySnapshot = Tasks.await(
                db.collection("pairs")
                    .whereEqualTo("ID1", id1)
                    .whereEqualTo("ID2", id2)
                    .get()
            )

            if (!query.isEmpty) {
                val doc = query.documents.first()
                Log.d("FirestoreRepository", "Found existing pair: ${doc.id}")
                return PairData(
                    id = doc.id,
                    ID1 = doc.getString("ID1") ?: "",
                    ID2 = doc.getString("ID2") ?: "",
                    bonded1 = doc.getBoolean("Bonded1") ?: false,
                    bonded2 = doc.getBoolean("Bonded2") ?: false,
                    sim_score = doc.getDouble("sim_score") ?: 0.0,
                    time = doc.getTimestamp("time")
                )
            }

            Log.d("FirestoreRepository", "No existing pair found, rechecking before create...")

            // Recheck immediately before creating (handles race condition)
            val secondCheck = Tasks.await(
                db.collection("pairs")
                    .whereEqualTo("ID1", id1)
                    .whereEqualTo("ID2", id2)
                    .get()
            )

            if (!secondCheck.isEmpty) {
                val doc = secondCheck.documents.first()
                Log.d("FirestoreRepository", "Found existing pair on recheck: ${doc.id}")
                return PairData(
                    id = doc.id,
                    ID1 = doc.getString("ID1") ?: "",
                    ID2 = doc.getString("ID2") ?: "",
                    bonded1 = doc.getBoolean("Bonded1") ?: false,
                    bonded2 = doc.getBoolean("Bonded2") ?: false,
                    sim_score = doc.getDouble("sim_score") ?: 0.0,
                    time = doc.getTimestamp("time")
                )
            }

            Log.d("FirestoreRepository", "No match even after recheck → creating new...")
            return createPair(id1, id2, simScore, 0, Timestamp.now())

        } catch (e: Exception) {
            Log.e("FirestoreRepository", "Error searching/creating pair: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            // Release the in-progress lock
            activeLookups.remove(key)
        }
    }

    /**
     * Find or create a Firestore pair document safely (no duplicates) with callback.
     * This function can be called from the main thread.
     */
    fun getOrCreatePairAsync(otherID: String, simScore: Double = 0.0, callback: (PairData?) -> Unit) {
        Thread {
            val result = getOrCreatePair(otherID, simScore)
            callback(result)
        }.start()
    }

    fun updStatus(otherID: String, bonding: Boolean){
        Thread {
            val currentUser = auth.currentUser ?: return@Thread
            val myID = currentUser.email?.substringBefore("@") ?: return@Thread
            val pair = getOrCreatePair(otherID)
            
            if (pair != null) {
                // Update the document in Firestore
                val updates = hashMapOf<String, Any>()
                if (myID < otherID) { // you are 1
                    updates["Bonded1"] = bonding
                } else {
                    updates["Bonded2"] = bonding
                }
                
                // Update the bonding status first
                db.collection("pairs").document(pair.id)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d("FirestoreRepository", "Successfully updated bonding status")
                        
                        // Now handle the incoming/bonded sets based on the new state
                        val newBonded1 = if (myID < otherID) bonding else pair.bonded1
                        val newBonded2 = if (myID >= otherID) bonding else pair.bonded2
                        val id1 = if(myID < otherID) myID else otherID
                        val id2 = if(myID < otherID) otherID else myID
                        if (newBonded1 == false && newBonded2 == false) {
                            // Both not bonded - remove from both users' sets
                            removeFromUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == false && newBonded2 == true) {
                            // Only 2 is bonded - 2 goes to 1's incoming
                            addToUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == true && newBonded2 == false) {
                            // Only 1 is bonded - 2 goes to 1's incoming, 1 goes to 2's incoming
                            removeFromUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            addToUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == true && newBonded2 == true) {
                            // Both bonded - move to bonded sets
                            removeFromUserIncoming(id1, id2)
                            addToUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            addToUserBonded(id2, id1)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreRepository", "Failed to update bonding status: ${e.message}")
                    }
            }
        }.start()
    }

    /**
     * Get user data by username from the users collection
     */
    fun getUserByUsername(username: String, callback: (UserData?) -> Unit) {
        Thread {
            try {
                val query = Tasks.await(
                    db.collection("users")
                        .whereEqualTo("username", username)
                        .get()
                )
                
                if (!query.isEmpty) {
                    val doc = query.documents.first()
                    val userData = UserData(
                        email = doc.getString("email") ?: "",
                        username = doc.getString("username") ?: "",
                        incoming = (doc.get("incoming") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                        bonded = (doc.get("bonded") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                    )
                    callback(userData)
                } else {
                    Log.w("FirestoreRepository", "No user found with username: $username")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "Error getting user by username: ${e.message}")
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }

    /**
     * Add a user to another user's incoming set
     */
    fun addToUserIncoming(targetUsername: String, addingUsername: String) {
        db.collection("users")
            .whereEqualTo("username", targetUsername)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val doc = querySnapshot.documents.first()
                    doc.reference.update("incoming", com.google.firebase.firestore.FieldValue.arrayUnion(addingUsername))
                        .addOnSuccessListener {
                            Log.d("FirestoreRepository", "Successfully added $addingUsername to $targetUsername's incoming set")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirestoreRepository", "Failed to add to incoming set: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepository", "Failed to find user: ${e.message}")
            }
    }

    /**
     * Add a user to another user's bonded set
     */
    fun addToUserBonded(targetUsername: String, addingUsername: String) {
        db.collection("users")
            .whereEqualTo("username", targetUsername)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val doc = querySnapshot.documents.first()
                    doc.reference.update("bonded", com.google.firebase.firestore.FieldValue.arrayUnion(addingUsername))
                        .addOnSuccessListener {
                            Log.d("FirestoreRepository", "Successfully added $addingUsername to $targetUsername's bonded set")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirestoreRepository", "Failed to add to bonded set: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepository", "Failed to find user: ${e.message}")
            }
    }

    /**
     * Remove a user from another user's incoming set
     */
    fun removeFromUserIncoming(targetUsername: String, removingUsername: String) {
        db.collection("users")
            .whereEqualTo("username", targetUsername)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val doc = querySnapshot.documents.first()
                    doc.reference.update("incoming", com.google.firebase.firestore.FieldValue.arrayRemove(removingUsername))
                        .addOnSuccessListener {
                            Log.d("FirestoreRepository", "Successfully removed $removingUsername from $targetUsername's incoming set")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirestoreRepository", "Failed to remove from incoming set: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepository", "Failed to find user: ${e.message}")
            }
    }

    /**
     * Remove a user from another user's bonded set
     */
    fun removeFromUserBonded(targetUsername: String, removingUsername: String) {
        db.collection("users")
            .whereEqualTo("username", targetUsername)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!querySnapshot.isEmpty) {
                    val doc = querySnapshot.documents.first()
                    doc.reference.update("bonded", com.google.firebase.firestore.FieldValue.arrayRemove(removingUsername))
                        .addOnSuccessListener {
                            Log.d("FirestoreRepository", "Successfully removed $removingUsername from $targetUsername's bonded set")
                        }
                        .addOnFailureListener { e ->
                            Log.e("FirestoreRepository", "Failed to remove from bonded set: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreRepository", "Failed to find user: ${e.message}")
            }
    }

    /**
     * Get all users from a user's incoming list
     */
    fun getIncomingUsers(callback: (List<UserData>) -> Unit) {
        Thread {
            try {
                val currentUser = auth.currentUser ?: return@Thread
                val myID = currentUser.email?.substringBefore("@") ?: return@Thread
                
                val myUserQuery = Tasks.await(
                    db.collection("users")
                        .whereEqualTo("username", myID)
                        .get()
                )
                
                if (!myUserQuery.isEmpty) {
                    val myUserDoc = myUserQuery.documents.first()
                    val incomingUsernames = (myUserDoc.get("incoming") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    
                    if (incomingUsernames.isNotEmpty()) {
                        val usersQuery = Tasks.await(
                            db.collection("users")
                                .whereIn("username", incomingUsernames)
                                .get()
                        )
                        
                        val users = usersQuery.documents.mapNotNull { doc ->
                            UserData(
                                email = doc.getString("email") ?: "",
                                username = doc.getString("username") ?: "",
                                incoming = (doc.get("incoming") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                                bonded = (doc.get("bonded") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                            )
                        }
                        callback(users)
                    } else {
                        callback(emptyList())
                    }
                } else {
                    callback(emptyList())
                }
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "Error getting incoming users: ${e.message}")
                e.printStackTrace()
                callback(emptyList())
            }
        }.start()
    }

    /**
     * Get all users from a user's bonded list
     */
    fun getBondedUsers(callback: (List<UserData>) -> Unit) {
        Thread {
            try {
                val currentUser = auth.currentUser ?: return@Thread
                val myID = currentUser.email?.substringBefore("@") ?: return@Thread
                
                val myUserQuery = Tasks.await(
                    db.collection("users")
                        .whereEqualTo("username", myID)
                        .get()
                )
                
                if (!myUserQuery.isEmpty) {
                    val myUserDoc = myUserQuery.documents.first()
                    val bondedUsernames = (myUserDoc.get("bonded") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    
                    if (bondedUsernames.isNotEmpty()) {
                        val usersQuery = Tasks.await(
                            db.collection("users")
                                .whereIn("username", bondedUsernames)
                                .get()
                        )
                        
                        val users = usersQuery.documents.mapNotNull { doc ->
                            UserData(
                                email = doc.getString("email") ?: "",
                                username = doc.getString("username") ?: "",
                                incoming = (doc.get("incoming") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                                bonded = (doc.get("bonded") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                            )
                        }
                        callback(users)
                    } else {
                        callback(emptyList())
                    }
                } else {
                    callback(emptyList())
                }
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "Error getting bonded users: ${e.message}")
                e.printStackTrace()
                callback(emptyList())
            }
        }.start()
    }

    /**
     * Get bonded users with their time spent together, sorted by time (highest first)
     */
    fun getBondedUsersWithTime(callback: (List<BondedUserWithTime>) -> Unit) {
        Thread {
            try {
                val currentUser = auth.currentUser ?: return@Thread
                val myID = currentUser.email?.substringBefore("@") ?: return@Thread
                
                val myUserQuery = Tasks.await(
                    db.collection("users")
                        .whereEqualTo("username", myID)
                        .get()
                )
                
                if (!myUserQuery.isEmpty) {
                    val myUserDoc = myUserQuery.documents.first()
                    val bondedUsernames = (myUserDoc.get("bonded") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    
                    if (bondedUsernames.isNotEmpty()) {
                        val usersQuery = Tasks.await(
                            db.collection("users")
                                .whereIn("username", bondedUsernames)
                                .get()
                        )
                        
                        val usersWithTime = mutableListOf<BondedUserWithTime>()
                        
                        // For each bonded user, get their time data from pairs collection
                        for (doc in usersQuery.documents) {
                            val username = doc.getString("username") ?: ""
                            val email = doc.getString("email") ?: ""
                            
                            // Determine alphabetical order for pair lookup
                            val id1 = if (myID < username) myID else username
                            val id2 = if (myID < username) username else myID
                            
                            try {
                                val pairQuery = Tasks.await(
                                    db.collection("pairs")
                                        .whereEqualTo("ID1", id1)
                                        .whereEqualTo("ID2", id2)
                                        .get()
                                )
                                
                                val totalTimeMinutes = if (!pairQuery.isEmpty) {
                                    pairQuery.documents.first().getLong("totalTimeMinutes") ?: 0L
                                } else {
                                    0L
                                }
                                
                                usersWithTime.add(
                                    BondedUserWithTime(
                                        username = username,
                                        email = email,
                                        totalTimeMinutes = totalTimeMinutes
                                    )
                                )
                            } catch (e: Exception) {
                                Log.e("FirestoreRepository", "Error getting time for $username: ${e.message}")
                                // Add user with 0 time on error
                                usersWithTime.add(
                                    BondedUserWithTime(
                                        username = username,
                                        email = email,
                                        totalTimeMinutes = 0L
                                    )
                                )
                            }
                        }
                        
                        // Sort by time spent (highest first) and take top 3
                        val sortedUsers = usersWithTime.sortedByDescending { it.totalTimeMinutes }
                        callback(sortedUsers)
                    } else {
                        callback(emptyList())
                    }
                } else {
                    callback(emptyList())
                }
            } catch (e: Exception) {
                Log.e("FirestoreRepository", "Error getting bonded users with time: ${e.message}")
                e.printStackTrace()
                callback(emptyList())
            }
        }.start()
    }

    /**
     * Update the other user's bonding status instead of the current user's status.
     * This function updates the other user's side of the pair relationship.
     */
    fun updOtherStatus(otherID: String, bonding: Boolean) {
        Thread {
            val currentUser = auth.currentUser ?: return@Thread
            val myID = currentUser.email?.substringBefore("@") ?: return@Thread
            val pair = getOrCreatePair(otherID)
            
            if (pair != null) {
                // Update the document in Firestore - this time update the OTHER user's status
                val updates = hashMapOf<String, Any>()
                if (myID < otherID) { // I am 1, so update 2's status
                    updates["Bonded2"] = bonding
                } else { // I am 2, so update 1's status
                    updates["Bonded1"] = bonding
                }
                
                // Update the bonding status first
                db.collection("pairs").document(pair.id)
                    .update(updates)
                    .addOnSuccessListener {
                        Log.d("FirestoreRepository", "Successfully updated other user's bonding status")
                        
                        // Now handle the incoming/bonded sets based on the new state
                        val newBonded1 = if (myID < otherID) pair.bonded1 else bonding
                        val newBonded2 = if (myID >= otherID) pair.bonded2 else bonding
                        val id1 = if (myID < otherID) myID else otherID
                        val id2 = if (myID < otherID) otherID else myID
                        
                        if (newBonded1 == false && newBonded2 == false) {
                            // Both not bonded - remove from both users' sets
                            removeFromUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == false && newBonded2 == true) {
                            // Only 2 is bonded - 2 goes to 1's incoming
                            addToUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == true && newBonded2 == false) {
                            // Only 1 is bonded - 1 goes to 2's incoming
                            removeFromUserIncoming(id1, id2)
                            removeFromUserBonded(id1, id2)
                            addToUserIncoming(id2, id1)
                            removeFromUserBonded(id2, id1)
                        } else if (newBonded1 == true && newBonded2 == true) {
                            // Both bonded - move to bonded sets
                            removeFromUserIncoming(id1, id2)
                            addToUserBonded(id1, id2)
                            removeFromUserIncoming(id2, id1)
                            addToUserBonded(id2, id1)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreRepository", "Failed to update other user's bonding status: ${e.message}")
                    }
            }
        }.start()
    }

    /**
     * Create a new pair document.
     */
    private fun createPair(ID1: String, ID2: String, simScore: Double, totalTimeMinutes : Long, lastSeen : Timestamp): PairData? {
        return try {
            val data = hashMapOf(
                "ID1" to ID1,
                "ID2" to ID2,
                "Bonded1" to false,
                "Bonded2" to false,
                "sim_score" to simScore,
                "totalTimeMinutes" to totalTimeMinutes/2,
                "lastSeen" to lastSeen
            )

            val docRef = Tasks.await(db.collection("pairs").add(data))
            Log.d("FirestoreRepository", "✅ Created new pair: ${docRef.id}")
            PairData(docRef.id, ID1, ID2, false, false, simScore, Timestamp.now())

        } catch (e: Exception) {
            Log.e("FirestoreRepository", "❌ Failed to create pair: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
