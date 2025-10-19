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
import com.example.bond.screens.BondApi
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

data class NearbyUser(val name: String, val similarity: Int, val rssi: Int)

@Composable
fun LookingScreen(bleManager: BLEManager, timeTracker: TimeTracker) {
    val nearbyUsers = remember { mutableStateListOf<NearbyUser>() }
    val lastSeen = remember { mutableStateMapOf<String, Long>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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

    LaunchedEffect(Unit) {
        bleManager.onUserDetected = { detectedUsername, rssi ->
            val now = System.currentTimeMillis()
            lastSeen[detectedUsername] = now
            Log.d("LookingScreen", "Detected BLE user: $detectedUsername (rssi=$rssi)")

            timeTracker.updateTimeTracking(detectedUsername) {}

            val currentUser = FirebaseAuth.getInstance().currentUser
            val myID = currentUser?.email?.substringBefore("@") ?: ""

            FirestoreRepository.getUserByUsername(myID) { myUserData ->
                if (myUserData != null &&
                    !myUserData.bonded.contains(detectedUsername) &&
                    !myUserData.incoming.contains(detectedUsername)
                ) {
                    // Get similarity score using BondApi
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val similarity = BondApi.similarity(myID, detectedUsername)
                            val score = (similarity * 100).toInt()
                            
                            mainHandler.post {
                                val existingIndex = nearbyUsers.indexOfFirst { it.name == detectedUsername }
                                val updatedUser = NearbyUser(
                                    name = detectedUsername,
                                    similarity = score,
                                    rssi = rssi
                                )
                                if (existingIndex == -1) nearbyUsers.add(updatedUser)
                                else nearbyUsers[existingIndex] = updatedUser
                            }
                        } catch (e: Exception) {
                            Log.e("LookingScreen", "Error getting similarity for $detectedUsername: ${e.message}")
                            // Fallback to 0 similarity on error
                            mainHandler.post {
                                val existingIndex = nearbyUsers.indexOfFirst { it.name == detectedUsername }
                                val updatedUser = NearbyUser(
                                    name = detectedUsername,
                                    similarity = 0,
                                    rssi = rssi
                                )
                                if (existingIndex == -1) nearbyUsers.add(updatedUser)
                                else nearbyUsers[existingIndex] = updatedUser
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val expired = lastSeen.filterValues { now - it > 5000 }.keys
            if (expired.isNotEmpty()) {
                expired.forEach { userName ->
                    lastSeen.remove(userName)
                    nearbyUsers.removeAll { it.name == userName }
                }
            }
        }
    }

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nearby",
                    fontSize = 32.sp,
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

    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "flip"
    )

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.05f else 1f,
        animationSpec = spring(),
        label = "scale"
    )

    val signalColor = when {
        user.rssi > -60 -> Color(0xFF10B981)
        user.rssi > -75 -> Color(0xFFFBBF24)
        else -> Color(0xFFEF4444)
    }

    val cardGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8B5CF6).copy(alpha = 0.3f),
            Color(0xFFEC4899).copy(alpha = 0.2f)
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
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
                    onTap = { scope.launch { flipped = !flipped } }
                )
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
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
                    FrontCard(user, signalColor)
                } else {
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
        // Left: profile + name + bond score
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(100.dp)
        ) {
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

            Text(
                text = user.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Bond Score: ${user.similarity}%",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right: Signal + Similarities box
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Signal",
                    fontSize = 12.sp,
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

            Spacer(modifier = Modifier.weight(1f))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF8B5CF6).copy(alpha = 0.3f)
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
                        text = "Music • Movies • Coffee • Travel • Fitness",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
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

        Button(
            onClick = { FirestoreRepository.updStatus(user.name, true) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))
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
