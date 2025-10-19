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
import com.example.bond.BLEManager
import com.example.bond.TimeTracker
import com.example.bond.data.FirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class NearbyUser(val name: String, val similarity: Int, val rssi: Int)

@Composable
fun LookingScreen(bleManager: BLEManager, timeTracker: TimeTracker) {
    val nearbyUsers = remember { mutableStateListOf<NearbyUser>() }
    val lastSeen = remember { mutableStateMapOf<String, Long>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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

    // --- Listen for BLE detections ---
    LaunchedEffect(Unit) {
        bleManager.onUserDetected = { detectedUsername, rssi ->
            val now = System.currentTimeMillis()
            lastSeen[detectedUsername] = now

            Log.d("LookingScreen", "Detected BLE user: $detectedUsername (rssi=$rssi)")

            timeTracker.updateTimeTracking(detectedUsername) { result ->
                result.onSuccess { msg ->
                    Log.d("LookingScreen", "✅ TimeTracker: $msg")
                }
                result.onFailure { e ->
                    Log.d("LookingScreen", "⚠️ TimeTracker: ${e.message}")
                }
            }

            FirestoreRepository.getOrCreatePairAsync(detectedUsername) { pair ->
                if (pair != null) {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    val myID = currentUser?.email?.substringBefore("@") ?: ""

                    FirestoreRepository.getUserByUsername(myID) { myUserData ->
                        if (myUserData != null &&
                            !myUserData.bonded.contains(detectedUsername) &&
                            !myUserData.incoming.contains(detectedUsername)
                        ) {
                            mainHandler.post {
                                val existingIndex = nearbyUsers.indexOfFirst { it.name == detectedUsername }
                                val updatedUser = NearbyUser(
                                    name = detectedUsername,
                                    similarity = (pair.sim_score * 10).toInt(),
                                    rssi = rssi
                                )
                                if (existingIndex == -1) {
                                    nearbyUsers.add(updatedUser)
                                } else {
                                    nearbyUsers[existingIndex] = updatedUser
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Periodic cleanup ---
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val expired = lastSeen.filterValues { now - it > 5000 }.keys

            if (expired.isNotEmpty()) {
                Log.d("LookingScreen", "Removing inactive users: $expired")
                expired.forEach { userName ->
                    lastSeen.remove(userName)
                    nearbyUsers.removeAll { it.name == userName }
                }
            }
        }
    }

    // --- UI ---
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
                    text = "Nearby",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (nearbyUsers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color(0xFFEC4899),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Looking for nearby users...",
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
                    items(nearbyUsers.sortedByDescending { it.similarity }) { user ->
                        BondCard(user)
                    }
                }
            }
        }
    }
}

@Composable
fun BondCard(user: NearbyUser) {
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

    // Signal strength color
    val signalColor = when {
        user.rssi > -60 -> Color(0xFF10B981) // strong - green
        user.rssi > -75 -> Color(0xFFFBBF24) // medium - yellow
        else -> Color(0xFFEF4444)            // weak - red
    }

    // Gradient for card
    val cardGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8B5CF6).copy(alpha = 0.3f),
            Color(0xFFEC4899).copy(alpha = 0.2f)
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
                    FrontCard(user, signalColor)
                } else {
                    // Back of card
                    BackCard(user, onFlip = { flipped = false })
                }
            }
        }
    }
}

@Composable
fun FrontCard(user: NearbyUser, signalColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: Profile and name
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(100.dp)
        ) {
            // Profile picture placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8B5CF6),
                                Color(0xFFEC4899)
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

            // Similarity percentage under name
            Text(
                text = "Similarity: ${user.similarity}%",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right side: Signal and similarity
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Similarities Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF8B5CF6).copy(alpha = 0.5f)
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
                        text = "Music • Gaming • Coffee • Travel • Fitness",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }

            // Signal strength (no box, just text)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Distance Broadcast",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(signalColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Tap to connect",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun BackCard(user: NearbyUser, onFlip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connect with ${user.name}?",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Ask to Bond button
        Button(
            onClick = {
                FirestoreRepository.updStatus(user.name, true)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
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
                                Color(0xFF8B5CF6),
                                Color(0xFFEC4899)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ask to Bond",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}