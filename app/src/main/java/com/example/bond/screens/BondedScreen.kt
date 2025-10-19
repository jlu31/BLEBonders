package com.example.bond.screens

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bond.data.FirestoreRepository
import com.example.bond.screens.BondApi
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BondedUser(
    val name: String,
    val email: String,
    val score: Int = 0,
    val totalTimeMinutes: Long = 0L,
    val summary: String = "No summary available",
    val icebreakers: List<String> = listOf("No icebreakers available")
)

@Composable
fun BondedScreen() {
    val bondedUsers = remember { mutableStateListOf<BondedUser>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val db = FirebaseFirestore.getInstance()

    // Animated gradient background
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    // Load bonded users with time tracking data
    LaunchedEffect(Unit) {
        FirestoreRepository.getBondedUsers { users ->
            val currentUser = FirebaseAuth.getInstance().currentUser
            val myUsername = currentUser?.email?.substringBefore("@") ?: ""

            users.forEach { userData ->
                // Determine alphabetical order for pair lookup
                val id1 = if (myUsername < userData.username) myUsername else userData.username
                val id2 = if (myUsername < userData.username) userData.username else myUsername

                // Query the pairs collection to get time data
                db.collection("pairs")
                    .whereEqualTo("ID1", id1)
                    .whereEqualTo("ID2", id2)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        if (!querySnapshot.isEmpty) {
                            val pairDoc = querySnapshot.documents.first()
                            val totalTime = pairDoc.getLong("totalTimeMinutes") ?: 0L

                            // Get similarity score and summary using BondApi
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val result = BondApi.similarity(myUsername, userData.username)
                                    val score = (result.similarity * 100).toInt()

                                    mainHandler.post {
                                        val existingIndex = bondedUsers.indexOfFirst { it.name == userData.username }
                                        val bondedUser = BondedUser(
                                            name = userData.username,
                                            email = userData.email,
                                            score = score,
                                            totalTimeMinutes = totalTime,
                                            summary = result.summary,
                                            icebreakers = result.icebreakers
                                        )

                                        if (existingIndex == -1) {
                                            bondedUsers.add(bondedUser)
                                        } else {
                                            bondedUsers[existingIndex] = bondedUser
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("BondedScreen", "Error getting similarity: ${e.message}")
                                    mainHandler.post {
                                        val existingIndex = bondedUsers.indexOfFirst { it.name == userData.username }
                                        val bondedUser = BondedUser(
                                            name = userData.username,
                                            email = userData.email,
                                            score = 0,
                                            totalTimeMinutes = totalTime,
                                            summary = "No summary available",
                                            icebreakers = listOf("No icebreakers available")
                                        )

                                        if (existingIndex == -1) {
                                            bondedUsers.add(bondedUser)
                                        } else {
                                            bondedUsers[existingIndex] = bondedUser
                                        }
                                    }
                                }
                            }
                        } else {
                            // No pair doc yet
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val result = BondApi.similarity(myUsername, userData.username)
                                    val score = (result.similarity * 100).toInt()

                                    mainHandler.post {
                                        bondedUsers.add(
                                            BondedUser(
                                                name = userData.username,
                                                email = userData.email,
                                                score = score,
                                                totalTimeMinutes = 0L,
                                                summary = result.summary,
                                                icebreakers = result.icebreakers
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("BondedScreen", "Error getting similarity: ${e.message}")
                                    mainHandler.post {
                                        bondedUsers.add(
                                            BondedUser(
                                                name = userData.username,
                                                email = userData.email,
                                                score = 0,
                                                totalTimeMinutes = 0L,
                                                summary = "No summary available",
                                                icebreakers = listOf("No icebreakers available")
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("BondedScreen", "Error fetching time data: ${e.message}")
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val result = BondApi.similarity(myUsername, userData.username)
                                val score = (result.similarity * 100).toInt()

                                mainHandler.post {
                                    bondedUsers.add(
                                        BondedUser(
                                            name = userData.username,
                                            email = userData.email,
                                            score = score,
                                            totalTimeMinutes = 0L,
                                            summary = result.summary,
                                            icebreakers = result.icebreakers
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e("BondedScreen", "Error getting similarity: ${e.message}")
                                mainHandler.post {
                                    bondedUsers.add(
                                        BondedUser(
                                            name = userData.username,
                                            email = userData.email,
                                            score = 0,
                                            totalTimeMinutes = 0L,
                                            summary = "No summary available",
                                            icebreakers = listOf("No icebreakers available")
                                        )
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F0F1E),
                            Color(0xFF1A1A2E),
                            Color(0xFF16213E)
                        ),
                        start = Offset(animatedOffset, animatedOffset),
                        end = Offset(animatedOffset + 500f, animatedOffset + 500f)
                    )
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp, bottom = 45.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Your Bonds",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (bondedUsers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No bonds yet",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(bondedUsers) { user ->
                            BondedCard(
                                user = user,
                                onUnbond = {
                                    bondedUsers.remove(user)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BondedCard(
    user: BondedUser,
    onUnbond: () -> Unit
) {
    var flipped by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Flip animation
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "flip"
    )

    // Press animation
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )

    // Gradient for card
    val cardGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF10B981).copy(alpha = 0.3f),
            Color(0xFF34D399).copy(alpha = 0.2f)
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .scale(scale)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        scope.launch {
                            flipped = !flipped
                        }
                    }
                )
            },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cardGradient)
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    rotationY = if (rotation > 90f) 180f else 0f
                }
            ) {
                if (rotation <= 90f) {
                    // Front of card
                    BondedFrontCard(user)
                } else {
                    // Back of card
                    BondedBackCard(
                        user = user,
                        onUnbond = {
                            FirestoreRepository.updOtherStatus(user.name, false)
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(500)
                                FirestoreRepository.updStatus(user.name, false)
                            }
                            onUnbond()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BondedFrontCard(user: BondedUser) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Profile + name + bond score
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(100.dp)
        ) {
            // Profile picture
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF10B981),
                                Color(0xFF34D399)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.name.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Username
            Text(
                text = user.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            // Bond Score under name (like LookingScreen)
            Text(
                text = "Similarities: ${user.score}%",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right side: Similarities + Time Together
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Similarities Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF10B981).copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Similarities",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = user.summary,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }

            // Time Together (no box, just text)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Format time
                val hours = user.totalTimeMinutes / 60
                val minutes = user.totalTimeMinutes % 60

                val timeText = when {
                    hours > 0 -> "${hours}h ${minutes}m"
                    minutes > 0 -> "${minutes}m"
                    else -> "0m"
                }

                Text(
                    text = timeText,
                    fontSize = 20.sp,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Time spent together (formatted for bottom text)
            val hours = user.totalTimeMinutes / 60
            val minutes = user.totalTimeMinutes % 60
            val timeText = when {
                hours > 0 -> "Together for ${hours}h ${minutes}m"
                minutes > 0 -> "Together for ${minutes}m"
                else -> "Just started bonding"
            }

            Text(
                text = timeText,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun BondedBackCard(
    user: BondedUser,
    onUnbond: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Ice Breakers Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = user.icebreakers.firstOrNull() ?: "No icebreakers available",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Unbond button
        Button(
            onClick = onUnbond,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFEF4444),
                                Color(0xFFF87171)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Unbond 💔",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}