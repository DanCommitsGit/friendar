package io.github.dancommitsgit.friendar.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.window.SplashScreenView
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.toPath
import io.github.dancommitsgit.friendar.R
import kotlin.math.roundToInt

// The splash logo drawables (res/drawable/splash_logo*.xml) are 288dp boxes; the disc and the arrow
// sit at these places in them. The arrow turns about the middle of its axis, from its tip to the line
// between its wing tips.
private const val BOX_DP = 288f
private const val DISC_START = 64f / 288f
private const val DISC_SIZE = 160f / 288f
private const val PIVOT_X = 120.4f / 288f
private const val PIVOT_Y = 167.6f / 288f
private const val AXIS = 56.94f / 288f

/** The logo arrow's corners as in res/drawable/ic_launcher_foreground.xml (see Arrow.kt for the order), before the box transform. */
private val LOGO_ARROW = floatArrayOf(
    55.29f, 62.59f, 58.97f, 61.03f, 57.41f, 64.71f,
    42.85f, 99.07f, 41.29f, 102.75f, 39.94f, 98.99f,
    36.28f, 88.83f, 34.93f, 85.07f, 31.17f, 83.72f,
    21.01f, 80.06f, 17.25f, 78.71f, 20.93f, 77.15f,
)

/** The logo arrow points up and to the right; the home screen arrow points up. */
private const val ARROW_TURN = -45f

/** Where the home screen arrow's tip and wing tips sit in its box (see Arrow.kt). */
private const val HERO_CENTER_Y = 0.47f
private const val HERO_AXIS = 0.86f

private const val MOVE_MS = 650L
private const val FADE_MS = 250L
/** How long after the first frame the hero shows anyway when the splash has not exited. */
internal const val EXIT_WAIT_MS = 300L

private enum class Stage { Pending, Entering, Done }

/**
 * Turns the splash logo into the home screen hero. While the splash is up the hero waits in
 * [Stage.Pending]; when the splash exits the logo's disc morphs into the spinning shape, its arrow
 * turns, grows and takes the shape of the real arrow, and the rest of the logo fades away.
 */
internal class SplashHandoff(pending: Boolean) {
    private var stage by mutableStateOf(if (pending) Stage.Pending else Stage.Done)

    /** 0 while the splash covers the hero's shape and arrow, 1 once they are drawn by the hero itself. */
    var swap by mutableFloatStateOf(if (pending) 0f else 1f)
        private set

    /** How far the hero's text has faded in. */
    var reveal by mutableFloatStateOf(if (pending) 0f else 1f)
        private set

    /** Where the idle hero's arrow and shape sit in the window, and how they look; reported while it is on screen. */
    var arrowBounds: Rect? = null
    var arrowColor: Int = 0
    var blobBounds: Rect? = null
    var blobColor: Int = 0
    var blobTurn: () -> Float = { 0f }

    /** Shows the hero without a handoff; used when the splash never gets to exit. */
    fun skip() {
        if (stage == Stage.Pending) show()
    }

    private fun show() {
        stage = Stage.Done
        swap = 1f
        reveal = 1f
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    fun run(splash: SplashScreenView) {
        val arrowTo = arrowBounds
        val blobTo = blobBounds
        val icon = splash.iconView
        if (stage != Stage.Pending || arrowTo == null || blobTo == null || icon == null || icon.width == 0) {
            show()
            splash.animate().alpha(0f).setDuration(FADE_MS).withEndAction { splash.remove() }.start()
            return
        }

        stage = Stage.Entering
        // The icon view is the 192dp circle the system shows; the 288dp drawable is centred in it.
        val size = (BOX_DP * splash.resources.displayMetrics.density).roundToInt()
        val iconAt = IntArray(2).also(icon::getLocationInWindow)
        val splashAt = IntArray(2).also(splash::getLocationInWindow)
        val left = iconAt[0] - splashAt[0] + (icon.width - size) / 2
        val top = iconAt[1] - splashAt[1] + (icon.height - size) / 2

        val discFrom = RectF(left + DISC_START * size, top + DISC_START * size, left + (DISC_START + DISC_SIZE) * size, top + (DISC_START + DISC_SIZE) * size)
        val discTo = RectF(blobTo.left - splashAt[0], blobTo.top - splashAt[1], blobTo.right - splashAt[0], blobTo.bottom - splashAt[1])
        val morph = Morph(RoundedPolygon.circle(numVertices = 9).normalized(), MaterialShapes.Cookie9Sided)
        val disc = DiscView(splash.context, morph, discFrom, discTo)
        splash.addView(disc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val friend = ImageView(splash.context).apply {
            setImageResource(R.drawable.splash_logo_friend)
            scaleType = ImageView.ScaleType.FIT_XY
            splash.addView(this, FrameLayout.LayoutParams(size, size).apply {
                leftMargin = left
                topMargin = top
            })
        }

        val pivotX = left + PIVOT_X * size
        val pivotY = top + PIVOT_Y * size
        val arrowFrom = FloatArray(24) { i ->
            (if (i % 2 == 0) left else top) + (56f + 1.4667f * LOGO_ARROW[i]) / BOX_DP * size
        }
        val arrowEnd = arrowPoints(arrowTo.width, arrowTo.height).also {
            for (i in it.indices step 2) {
                it[i] += arrowTo.left - splashAt[0]
                it[i + 1] += arrowTo.top - splashAt[1]
            }
        }
        val arrow = ArrowView(
            splash.context, arrowFrom, arrowEnd, pivotX, pivotY,
            dx = arrowTo.center.x - splashAt[0] - pivotX,
            dy = arrowTo.top + HERO_CENTER_Y * arrowTo.height - splashAt[1] - pivotY,
            scale = HERO_AXIS * arrowTo.height / (AXIS * size),
        )
        splash.addView(arrow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        icon.alpha = 0f

        val arrowColorFrom = splash.context.getColor(R.color.ic_launcher_arrow)
        val discColor = splash.context.getColor(R.color.ic_launcher_background)
        val colors = ArgbEvaluator()
        val background = splash.background?.mutate()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS
            interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                val f = it.animatedFraction
                disc.update(t, blobTurn(), colors.evaluate(t, discColor, blobColor) as Int)
                arrow.update(t, colors.evaluate(t, arrowColorFrom, arrowColor) as Int)
                friend.alpha = 1f - (f / 0.5f).coerceAtMost(1f)
                reveal = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)
                background?.alpha = ((1f - f) * 255f).toInt()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    show()
                    // The hero's own shape and arrow sit under the overlays for a frame or two before the splash goes.
                    splash.postOnAnimation { splash.postOnAnimation { splash.remove() } }
                }
            })
            start()
        }
    }
}

/**
 * Draws [morph] in a box that slides from [from] to [to], turning as it goes. The end shape is placed
 * the way Compose places a polygon shape: scaled to the box and centred in it. The start circle's
 * outline does not quite fill its unit box, so it is scaled up to measure exactly [from].
 */
private class DiscView(context: Context, private val morph: Morph, private val from: RectF, private val to: RectF) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val matrix = Matrix()
    private val startScaleX: Float
    private val startScaleY: Float
    private var progress = 0f
    private var turn = 0f

    init {
        morph.toPath(0f, path)
        path.computeBounds(bounds, true)
        startScaleX = from.width() / bounds.width()
        startScaleY = from.height() / bounds.height()
    }

    fun update(progress: Float, turn: Float, color: Int) {
        this.progress = progress
        this.turn = turn
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = lerp(from.centerX(), to.centerX())
        val centerY = lerp(from.centerY(), to.centerY())
        path.rewind()
        morph.toPath(progress, path)
        matrix.setScale(lerp(startScaleX, to.width()), lerp(startScaleY, to.height()))
        path.transform(matrix)
        path.computeBounds(bounds, true)
        path.offset(centerX - bounds.centerX(), centerY - bounds.centerY())
        canvas.save()
        canvas.rotate(turn * progress, centerX, centerY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * progress
}

/**
 * Draws the logo's arrow turning, growing and sliding onto the home screen arrow's place, while its
 * outline settles into the home screen arrow's outline so the two match exactly when it arrives.
 */
private class ArrowView(
    context: Context,
    private val from: FloatArray,
    private val to: FloatArray,
    private val pivotX: Float,
    private val pivotY: Float,
    private val dx: Float,
    private val dy: Float,
    private val scale: Float,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val matrix = Matrix()
    private val points = FloatArray(24)
    private val landed = FloatArray(24)
    private var progress = 0f

    init {
        place(1f, landed)
    }

    fun update(progress: Float, color: Int) {
        this.progress = progress
        paint.color = color
        invalidate()
    }

    private fun place(t: Float, out: FloatArray) {
        val s = 1f + (scale - 1f) * t
        matrix.setRotate(ARROW_TURN * t, pivotX, pivotY)
        matrix.postScale(s, s, pivotX, pivotY)
        matrix.postTranslate(dx * t, dy * t)
        matrix.mapPoints(out, from)
    }

    override fun onDraw(canvas: Canvas) {
        place(progress, points)
        for (i in points.indices) points[i] += (to[i] - landed[i]) * progress
        canvas.drawPath(arrowPath(points, path), paint)
    }
}

private val EASE = PathInterpolator(0.3f, 0f, 0.15f, 1f)

/** Previews and anything outside the launch flow see the hero without a handoff. */
internal val LocalSplashHandoff = staticCompositionLocalOf { SplashHandoff(pending = false) }
