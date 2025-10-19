package com.example.bond.screens

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class RequestUser(val name: String, val email: String)

data class ConfettiParticle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val rotation: Float
)

@Composable
fun RequestsScreen() {
    val incomingUsers = remember { mutableStateListOf<RequestUser>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var showConfetti by remember { mutableStateOf(false) }
    var bondedUsername by remember { mutableStateOf("") }

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

    // Load incoming users
    LaunchedEffect(Unit) {
        FirestoreRepository.getIncomingUsers { users ->
            mainHandler.post {
                incomingUsers.clear()
                incomingUsers.addAll(
                    users.map { userData ->
                        RequestUser(
                            name = userData.username,
                            email = userData.email
                        )
                    }
                )
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
                        text = "Bond Requests",
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (incomingUsers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No pending requests",
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
                        items(incomingUsers) { user ->
                            RequestCard(
                                user = user,
                                onBonded = { username ->
                                    bondedUsername = username
                                    showConfetti = true
                                    incomingUsers.remove(user)
                                },
                                onIgnored = {
                                    incomingUsers.remove(user)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Confetti overlay
        if (showConfetti) {
            ConfettiAnimation(
                bondedUsername = bondedUsername,
                onComplete = { showConfetti = false }
            )
        }
    }
}

@Composable
fun RequestCard(
    user: RequestUser,
    onBonded: (String) -> Unit,
    onIgnored: () -> Unit
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
            Color(0xFFFF6B6B).copy(alpha = 0.3f),
            Color(0xFFFFAA00).copy(alpha = 0.2f)
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
                    RequestFrontCard(user)
                } else {
                    // Back of card
                    RequestBackCard(
                        user = user,
                        onBond = {
                            FirestoreRepository.updStatus(user.name, true)
                            onBonded(user.name)
                        },
                        onIgnore = {
                            val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            val myID = currentUser?.email?.substringBefore("@") ?: ""
                            FirestoreRepository.removeFromUserIncoming(myID, user.name)
                            FirestoreRepository.updOtherStatus(user.name, false)
                            onIgnored()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RequestFrontCard(user: RequestUser) {
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
                                Color(0xFFFF6B6B),
                                Color(0xFFFFAA00)
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

            // Bond Score under name
            Text(
                text = "Bond Score: 0%",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right side: Similarities
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Similarities Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFF6B6B).copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
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

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = "Tap to respond",
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun RequestBackCard(user: RequestUser, onBond: () -> Unit, onIgnore: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Accept ${user.name}'s request?",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Bond button
        Button(
            onClick = onBond,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
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
                                Color(0xFF10B981),
                                Color(0xFF34D399)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Bond 💚",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(11.dp))

        // Ignore button
        Button(
            onClick = onIgnore,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
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
                    "Ignore",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun ConfettiAnimation(bondedUsername: String, onComplete: () -> Unit) {
    var particles by remember {
        mutableStateOf(
            List(100) {
                val angle = Random.nextFloat() * 2 * Math.PI.toFloat()
                val speed = Random.nextFloat() * 5f + 3f
                ConfettiParticle(
                    x = 0.5f,
                    y = 0.5f,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 5f,
                    color = listOf(
                        Color(0xFFFF6B6B),
                        Color(0xFFFFAA00),
                        Color(0xFF8B5CF6),
                        Color(0xFFEC4899),
                        Color(0xFF10B981),
                        Color(0xFF3B82F6)
                    ).random(),
                    rotation = Random.nextFloat() * 360f
                )
            }
        )
    }

    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 3000) {
            delay(16)
            particles = particles.map { p ->
                p.copy(
                    x = p.x + p.vx * 0.01f,
                    y = p.y + p.vy * 0.01f,
                    vy = p.vy + 0.2f,
                    rotation = p.rotation + 5f
                )
            }
        }
        onComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            particles.forEach { p ->
                val px = p.x * size.width
                val py = p.y * size.height
                drawCircle(
                    color = p.color,
                    radius = 8f,
                    center = Offset(px, py)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🎉",
                fontSize = 80.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "You've bonded!",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "with $bondedUsername",
                fontSize = 20.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }
    }
}