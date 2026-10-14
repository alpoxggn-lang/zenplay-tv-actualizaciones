package tv.own.owntv.mobile.zenplay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.own.owntv.mobile.R

/** The animated ZenPlay mark: the logo breathing over a blue/violet glow with a ring of light circling it. */
@Composable
fun ZenPlayLogoLoader(size: Int = 220, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "zenLogo")
    val pulse by t.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val spin by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "spin",
    )
    val glow by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF5B8CFF).copy(alpha = glow), Color(0xFF8A4DFF).copy(alpha = glow * 0.5f), Color.Transparent),
                    center = c,
                    radius = this.size.minDimension / 2,
                ),
                radius = this.size.minDimension / 2,
                center = c,
            )
            val w = (this.size.minDimension / 60f).coerceAtLeast(2.dp.toPx())
            val r = this.size.minDimension / 2 - w * 3
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, Color(0xFF45D6FF), Color(0xFFB07BFF)), center = c),
                startAngle = spin,
                sweepAngle = 250f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        Image(
            painter = painterResource(R.drawable.zenplay_logo),
            contentDescription = null,
            modifier = Modifier.size((size * 0.66f).dp).scale(pulse),
        )
    }
}

/** The welcome screen shown when ZenPlay opens: the animated logo and "Cargando tu contenido…". */
@Composable
fun ZenPlaySplash(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "zenSplash")
    val dots by t.animateFloat(
        initialValue = 0f,
        targetValue = 3.99f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "dots",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF151A4A), Color(0xFF070B1A)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZenPlayLogoLoader(size = 240)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.zenplay_splash_loading) + ".".repeat(dots.toInt()),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

/** The welcome screen is shown once per app start. */
object ZenSplashState {
    @Volatile var shown: Boolean = false
}
