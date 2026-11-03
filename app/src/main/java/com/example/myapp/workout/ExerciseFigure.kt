package com.example.myapp.workout

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

private const val BOX_W = 100f
private const val BOX_H = 72f

/**
 * A stick figure's joints in a 100 x 72 box, seen from the side (pull ups from the front). The
 * near limbs are drawn full, the far ones faded.
 */
private class Pose(
    val head: Offset,
    val neck: Offset,
    val hip: Offset,
    val nearKnee: Offset, val nearFoot: Offset,
    val farKnee: Offset, val farFoot: Offset,
    val nearElbow: Offset, val nearHand: Offset,
    val farElbow: Offset, val farHand: Offset
) {
    fun lerp(to: Pose, t: Float): Pose {
        fun Offset.to(o: Offset) = Offset(x + (o.x - x) * t, y + (o.y - y) * t)
        return Pose(
            head.to(to.head), neck.to(to.neck), hip.to(to.hip),
            nearKnee.to(to.nearKnee), nearFoot.to(to.nearFoot), farKnee.to(to.farKnee), farFoot.to(to.farFoot),
            nearElbow.to(to.nearElbow), nearHand.to(to.nearHand), farElbow.to(to.farElbow), farHand.to(to.farHand)
        )
    }
}

private fun p(x: Float, y: Float) = Offset(x, y)

private val GROUND = listOf(p(4f, 67.5f) to p(96f, 67.5f))
private val BAR = listOf(p(28f, 10f) to p(72f, 10f))
private val CHAIR = GROUND + listOf(p(16f, 40f) to p(39f, 40f), p(18f, 40f) to p(18f, 67.5f), p(36f, 40f) to p(36f, 67.5f))

/**
 * The two ends of a move, how long one way takes, and the props drawn behind the figure.
 * [look] and [lookTo] are where the face points at each end (belly down or up reads from it),
 * [mat] lays a mat on the floor, [front] draws both sides full and two eyes, for a figure seen
 * from the front.
 */
private class Move(
    val from: Pose,
    val to: Pose,
    val ms: Int,
    val look: Offset = p(1f, 0f),
    val lookTo: Offset = look,
    val props: List<Pair<Offset, Offset>> = GROUND,
    val mat: Boolean = false,
    val front: Boolean = false
)

private val PUSH_UP = Move(
    from = Pose(
        head = p(82f, 36f), neck = p(73f, 41f), hip = p(45f, 52f),
        nearKnee = p(30f, 58f), nearFoot = p(15f, 64f), farKnee = p(31f, 58f), farFoot = p(17f, 64f),
        nearElbow = p(73f, 54f), nearHand = p(73f, 66f), farElbow = p(75f, 54f), farHand = p(75f, 66f)
    ),
    to = Pose(
        head = p(83f, 52f), neck = p(73f, 56f), hip = p(45f, 60f),
        nearKnee = p(30f, 62f), nearFoot = p(15f, 64f), farKnee = p(31f, 62f), farFoot = p(17f, 64f),
        nearElbow = p(65f, 60f), nearHand = p(73f, 66f), farElbow = p(67f, 60f), farHand = p(75f, 66f)
    ),
    ms = 1100,
    look = p(0.5f, 0.85f),
    mat = true
)

private val SQUAT = Move(
    from = Pose(
        head = p(51f, 7f), neck = p(50f, 14f), hip = p(49f, 32f),
        nearKnee = p(50f, 48f), nearFoot = p(50f, 66f), farKnee = p(52f, 48f), farFoot = p(52f, 66f),
        nearElbow = p(59f, 15f), nearHand = p(68f, 16f), farElbow = p(60f, 16f), farHand = p(69f, 17f)
    ),
    to = Pose(
        head = p(57f, 32f), neck = p(53f, 38f), hip = p(42f, 52f),
        nearKnee = p(58f, 50f), nearFoot = p(50f, 66f), farKnee = p(60f, 50f), farFoot = p(52f, 66f),
        nearElbow = p(62f, 39f), nearHand = p(72f, 39f), farElbow = p(63f, 40f), farHand = p(73f, 40f)
    ),
    ms = 1300,
    lookTo = p(1f, 0.25f)
)

private val SINGLE_LEG_DEADLIFT = Move(
    from = Pose(
        head = p(50f, 7f), neck = p(50f, 14f), hip = p(50f, 32f),
        nearKnee = p(50f, 49f), nearFoot = p(50f, 66f), farKnee = p(47f, 49f), farFoot = p(44f, 63f),
        nearElbow = p(51f, 23f), nearHand = p(52f, 32f), farElbow = p(49f, 23f), farHand = p(48f, 32f)
    ),
    to = Pose(
        head = p(76f, 30f), neck = p(68f, 32f), hip = p(51f, 34f),
        nearKnee = p(53f, 50f), nearFoot = p(50f, 66f), farKnee = p(34f, 33f), farFoot = p(17f, 31f),
        nearElbow = p(68f, 41f), nearHand = p(68f, 50f), farElbow = p(66f, 41f), farHand = p(66f, 50f)
    ),
    ms = 1700,
    lookTo = p(0.5f, 0.85f)
)

/** Held still but for a slow breath, the back lifting a little. */
private val PLANK = Move(
    from = Pose(
        head = p(81f, 45f), neck = p(72f, 49f), hip = p(45f, 56f),
        nearKnee = p(30f, 60f), nearFoot = p(15f, 64f), farKnee = p(31f, 60f), farFoot = p(17f, 64f),
        nearElbow = p(72f, 66f), nearHand = p(85f, 66f), farElbow = p(74f, 66f), farHand = p(87f, 66f)
    ),
    to = Pose(
        head = p(81f, 44f), neck = p(72f, 48.5f), hip = p(45f, 54.5f),
        nearKnee = p(30f, 59.5f), nearFoot = p(15f, 64f), farKnee = p(31f, 59.5f), farFoot = p(17f, 64f),
        nearElbow = p(72f, 66f), nearHand = p(85f, 66f), farElbow = p(74f, 66f), farHand = p(87f, 66f)
    ),
    ms = 2200,
    look = p(0.4f, 0.9f),
    mat = true
)

private val BIRD_DOG = Move(
    from = Pose(
        head = p(79f, 41f), neck = p(70f, 46f), hip = p(40f, 46f),
        nearKnee = p(40f, 66f), nearFoot = p(24f, 66f), farKnee = p(42f, 66f), farFoot = p(26f, 66f),
        nearElbow = p(70f, 56f), nearHand = p(70f, 66f), farElbow = p(72f, 56f), farHand = p(72f, 66f)
    ),
    to = Pose(
        head = p(79f, 41f), neck = p(70f, 46f), hip = p(40f, 46f),
        nearKnee = p(40f, 66f), nearFoot = p(24f, 66f), farKnee = p(25f, 45f), farFoot = p(8f, 44f),
        nearElbow = p(80f, 44f), nearHand = p(91f, 42f), farElbow = p(72f, 56f), farHand = p(72f, 66f)
    ),
    ms = 1500,
    look = p(0.6f, 0.8f),
    mat = true
)

private val PULL_UP = Move(
    from = Pose(
        head = p(50f, 22f), neck = p(50f, 30f), hip = p(50f, 50f),
        nearKnee = p(47f, 60f), nearFoot = p(47f, 70f), farKnee = p(53f, 60f), farFoot = p(53f, 70f),
        nearElbow = p(42f, 20f), nearHand = p(38f, 10f), farElbow = p(58f, 20f), farHand = p(62f, 10f)
    ),
    to = Pose(
        head = p(50f, 6f), neck = p(50f, 15f), hip = p(50f, 35f),
        nearKnee = p(47f, 45f), nearFoot = p(47f, 55f), farKnee = p(53f, 45f), farFoot = p(53f, 55f),
        nearElbow = p(31f, 19f), nearHand = p(38f, 10f), farElbow = p(69f, 19f), farHand = p(62f, 10f)
    ),
    ms = 1400,
    props = BAR,
    front = true
)

/** Hanging with straight arms, the shoulders pulled down so the body rises a little. */
private val SCAPULAR_PULL_UP = Move(
    from = Pose(
        head = p(50f, 23f), neck = p(50f, 31f), hip = p(50f, 51f),
        nearKnee = p(47f, 61f), nearFoot = p(47f, 71f), farKnee = p(53f, 61f), farFoot = p(53f, 71f),
        nearElbow = p(44f, 20.5f), nearHand = p(38f, 10f), farElbow = p(56f, 20.5f), farHand = p(62f, 10f)
    ),
    to = Pose(
        head = p(50f, 16f), neck = p(50f, 25f), hip = p(50f, 45f),
        nearKnee = p(47f, 55f), nearFoot = p(47f, 65f), farKnee = p(53f, 55f), farFoot = p(53f, 65f),
        nearElbow = p(44f, 17.5f), nearHand = p(38f, 10f), farElbow = p(56f, 17.5f), farHand = p(62f, 10f)
    ),
    ms = 1000,
    props = BAR,
    front = true
)

/** Hips high in an upside down V, the head lowered toward the floor between the hands. */
private val PIKE_PUSH_UP = Move(
    from = Pose(
        head = p(68f, 53f), neck = p(62f, 47f), hip = p(45f, 30f),
        nearKnee = p(33f, 47f), nearFoot = p(22f, 64f), farKnee = p(35f, 47f), farFoot = p(24f, 64f),
        nearElbow = p(66f, 57f), nearHand = p(70f, 66f), farElbow = p(68f, 57f), farHand = p(72f, 66f)
    ),
    to = Pose(
        head = p(66f, 61f), neck = p(60f, 55f), hip = p(44f, 34f),
        nearKnee = p(33f, 49f), nearFoot = p(22f, 64f), farKnee = p(35f, 49f), farFoot = p(24f, 64f),
        nearElbow = p(60f, 63f), nearHand = p(70f, 66f), farElbow = p(62f, 63f), farHand = p(72f, 66f)
    ),
    ms = 1200,
    look = p(-0.6f, 0.8f),
    mat = true
)

/** Face down, arms reaching forward, chest and legs lifted off the floor. */
private val SUPERMAN = Move(
    from = Pose(
        head = p(80f, 61f), neck = p(72f, 63f), hip = p(45f, 63f),
        nearKnee = p(28f, 64f), nearFoot = p(12f, 64f), farKnee = p(28f, 64f), farFoot = p(12f, 64f),
        nearElbow = p(82f, 63f), nearHand = p(92f, 63f), farElbow = p(82f, 63f), farHand = p(92f, 63f)
    ),
    to = Pose(
        head = p(78f, 55f), neck = p(70f, 59f), hip = p(45f, 63f),
        nearKnee = p(28f, 61f), nearFoot = p(13f, 57f), farKnee = p(28f, 61f), farFoot = p(13f, 57f),
        nearElbow = p(80f, 55f), nearHand = p(90f, 51f), farElbow = p(80f, 55f), farHand = p(90f, 51f)
    ),
    ms = 1800,
    look = p(0.2f, 1f),
    lookTo = p(0.5f, 0.85f),
    mat = true
)

/** Hands on a chair seat behind, feet on the floor, the body lowered by bending the elbows back. */
private val CHAIR_DIP = Move(
    from = Pose(
        head = p(42f, 16f), neck = p(40f, 24f), hip = p(42f, 42f),
        nearKnee = p(60f, 44f), nearFoot = p(64f, 66f), farKnee = p(61f, 44f), farFoot = p(66f, 66f),
        nearElbow = p(39f, 32f), nearHand = p(38f, 40f), farElbow = p(40f, 32f), farHand = p(39f, 40f)
    ),
    to = Pose(
        head = p(44f, 26f), neck = p(42f, 34f), hip = p(44f, 52f),
        nearKnee = p(61f, 50f), nearFoot = p(64f, 66f), farKnee = p(62f, 50f), farFoot = p(66f, 66f),
        nearElbow = p(30f, 36f), nearHand = p(38f, 40f), farElbow = p(31f, 36f), farHand = p(39f, 40f)
    ),
    ms = 1300,
    props = CHAIR
)

/** On the back, knees and arms up, one arm reaching overhead while the other side's leg stretches out. */
private val DEAD_BUG = Move(
    from = Pose(
        head = p(79f, 61f), neck = p(70f, 62f), hip = p(45f, 62f),
        nearKnee = p(45f, 46f), nearFoot = p(29f, 46f), farKnee = p(46f, 46f), farFoot = p(30f, 46f),
        nearElbow = p(70f, 52f), nearHand = p(70f, 42f), farElbow = p(71f, 52f), farHand = p(71f, 42f)
    ),
    to = Pose(
        head = p(79f, 61f), neck = p(70f, 62f), hip = p(45f, 62f),
        nearKnee = p(45f, 46f), nearFoot = p(29f, 46f), farKnee = p(30f, 59f), farFoot = p(14f, 58f),
        nearElbow = p(80f, 57f), nearHand = p(90f, 55f), farElbow = p(71f, 52f), farHand = p(71f, 42f)
    ),
    ms = 1600,
    look = p(0f, -1f),
    mat = true
)

/** The drawing for an exercise of the default program, found by its name; none for a custom one. */
private fun moveFor(name: String): Move? {
    val n = name.lowercase()
    return when {
        "scapular" in n -> SCAPULAR_PULL_UP
        "pull" in n -> PULL_UP
        "pike" in n -> PIKE_PUSH_UP
        "push" in n -> PUSH_UP
        "dip" in n -> CHAIR_DIP
        "superman" in n -> SUPERMAN
        "dead bug" in n -> DEAD_BUG
        "squat" in n -> SQUAT
        "deadlift" in n -> SINGLE_LEG_DEADLIFT
        "plank" in n -> PLANK
        "bird" in n -> BIRD_DOG
        else -> null
    }
}

/**
 * A figure looping slowly between the two ends of the exercise, both ends left faintly behind it
 * so the range of the move shows at a glance.
 */
@Composable
fun ExerciseFigure(name: String, color: Color, modifier: Modifier = Modifier) {
    val move = moveFor(name) ?: return
    val face = MaterialTheme.colorScheme.surfaceVariant
    val t by rememberInfiniteTransition(label = "figure").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(move.ms, delayMillis = 250, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pose"
    )
    Canvas(modifier) {
        val scale = minOf(size.width / BOX_W, size.height / BOX_H)
        val dx = (size.width - BOX_W * scale) / 2
        val dy = (size.height - BOX_H * scale) / 2
        fun Offset.px() = Offset(dx + x * scale, dy + y * scale)
        val stroke = 1.9f * scale
        val headRadius = 6f * scale
        val prop = color.copy(alpha = 0.3f)
        move.props.forEach { (a, b) -> drawLine(prop, a.px(), b.px(), stroke * 0.8f, StrokeCap.Round) }
        if (move.mat) drawLine(color.copy(alpha = 0.22f), p(6f, 66.5f).px(), p(94f, 66.5f).px(), 2.4f * scale, StrokeCap.Round)

        fun DrawScope.bone(c: Color, a: Offset, b: Offset, width: Float = stroke) =
            drawLine(c, a.px(), b.px(), width, StrokeCap.Round)
        fun DrawScope.limb(c: Color, root: Offset, joint: Offset, end: Offset) {
            bone(c, root, joint)
            bone(c, joint, end)
        }
        fun DrawScope.figure(pose: Pose, near: Color, far: Color) {
            limb(far, pose.hip, pose.farKnee, pose.farFoot)
            limb(far, pose.neck, pose.farElbow, pose.farHand)
            bone(near, pose.neck, pose.hip, stroke * 1.9f)
            limb(near, pose.hip, pose.nearKnee, pose.nearFoot)
            limb(near, pose.neck, pose.nearElbow, pose.nearHand)
            drawCircle(near, headRadius, pose.head.px())
        }

        val ghost = color.copy(alpha = 0.14f)
        figure(move.from, ghost, ghost)
        figure(move.to, ghost, ghost)

        val pose = move.from.lerp(move.to, t)
        figure(pose, color, if (move.front) color else color.copy(alpha = 0.45f))
        val head = pose.head.px()
        if (move.front) {
            for (side in listOf(-1f, 1f)) {
                drawCircle(face, headRadius * 0.17f, head + Offset(side * headRadius * 0.38f, -headRadius * 0.1f))
            }
        } else {
            val raw = Offset(move.look.x + (move.lookTo.x - move.look.x) * t, move.look.y + (move.lookTo.y - move.look.y) * t)
            val look = raw / raw.getDistance()
            drawCircle(face, headRadius * 0.2f, head + look * (headRadius * 0.5f))
            drawCircle(color, headRadius * 0.32f, head + look * (headRadius * 1.05f))
        }
    }
}
