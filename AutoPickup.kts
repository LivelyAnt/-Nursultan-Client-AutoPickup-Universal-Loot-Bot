
name("AutoPickup")
description("Автоподбор предметов, поиск пути и выход из воды")
requireApi(9)

val range by slider("Радиус поиска предметов", 40f, 8f, 40f, 1f)
val pathRadius by slider("Радиус поиска маршрута", 18f, 3f, 18f, 1f)
val maxJumpHeight by slider("Высота маршрута", 4f, 2f, 20f, 1f)
val turnSpeed by slider("Скорость камеры", 2f, 1f, 24f, 1f)
val rotateCamera by checkBox("Поворот камеры по движению", true)

data class Node(val x: Int, val y: Int, val z: Int)
data class SearchNode(val node: Node, val cost: Double, val score: Double)

var targetId = -1
var nextNode: Node? = null
var lastPathTick = -100L

fun floorInt(v: Double): Int = kotlin.math.floor(v).toInt()

fun normalizeAngle(angle: Double): Double {
    var a = angle % 360.0
    if (a >= 180.0) a -= 360.0
    if (a < -180.0) a += 360.0
    return a
}

fun isWater(n: Node): Boolean {
    val feet = world.block(n.x, n.y, n.z)
    val head = world.block(n.x, n.y + 1, n.z)

    return feet.fluid()?.id()?.contains("water") == true ||
        head.fluid()?.id()?.contains("water") == true
}

fun passable(n: Node): Boolean {
    val feet = world.block(n.x, n.y, n.z)
    val head = world.block(n.x, n.y + 1, n.z)
    val ground = world.block(n.x, n.y - 1, n.z)

    if (feet.blocksMovement() || head.blocksMovement()) return false
    if (!ground.hasCollision() && !isWater(n)) return false

    return world.isBlockSpaceFree(
        Box(
            n.x + 0.2, n.y.toDouble(), n.z + 0.2,
            n.x + 0.8, n.y + 1.8, n.z + 0.8
        )
    )
}

fun heuristic(a: Node, b: Node): Double {
    return kotlin.math.abs(a.x - b.x) +
        kotlin.math.abs(a.z - b.z) +
        kotlin.math.abs(a.y - b.y) * 1.3
}

fun findPath(start: Node, target: Node): Node? {
    val open = java.util.PriorityQueue<SearchNode>(
        compareBy<SearchNode> { it.score }
    )

    val parent = HashMap<Node, Node>()
    val costs = HashMap<Node, Double>()
    val closed = HashSet<Node>()

    costs[start] = 0.0
    open.add(SearchNode(start, 0.0, heuristic(start, target)))

    var reached: Node? = null
    var checked = 0

    while (open.isNotEmpty() && checked < 5000) {
        val entry = open.poll()
        val current = entry.node

        if (current in closed) continue

        val bestCost = costs[current] ?: continue
        if (entry.cost > bestCost) continue

        closed.add(current)
        checked++

        if (current != start &&
            kotlin.math.abs(current.x - target.x) <= 1 &&
            kotlin.math.abs(current.z - target.z) <= 1 &&
            kotlin.math.abs(current.y - target.y) <= 1
        ) {
            reached = current
            break
        }

        for ((dx, dz) in listOf(
            Pair(1, 0),
            Pair(-1, 0),
            Pair(0, 1),
            Pair(0, -1)
        )) {
            for (dy in (-maxJumpHeight.toInt()..maxJumpHeight.toInt()).reversed()) {
                val next = Node(
                    current.x + dx,
                    current.y + dy,
                    current.z + dz
                )

                if (kotlin.math.abs(next.x - start.x) > pathRadius.toInt()) continue
                if (kotlin.math.abs(next.z - start.z) > pathRadius.toInt()) continue
                if (kotlin.math.abs(next.y - start.y) > 40) continue
                if (next in closed || !passable(next)) continue

                if (dy < -1 && !isWater(next)) {
                    var clear = true

                    for (y in (next.y + 1)..current.y) {
                        if (world.block(next.x, y, next.z).blocksMovement()) {
                            clear = false
                            break
                        }
                    }

                    if (!clear) continue
                }

                if (dy > 1) {
                    var clear = true

                    for (y in (current.y + 1)..(next.y + 1)) {
                        if (world.block(next.x, y, next.z).blocksMovement()) {
                            clear = false
                            break
                        }
                    }

                    if (!clear) continue
                }

                var stepCost = 1.0

                if (dy > 0) stepCost += 1.5 * dy
                if (dy < 0) stepCost += kotlin.math.abs(dy) * 0.25
                if (isWater(next)) stepCost += 1.0

                val newCost = bestCost + stepCost
                val oldCost = costs[next]

                if (oldCost == null || newCost < oldCost) {
                    costs[next] = newCost
                    parent[next] = current

                    open.add(
                        SearchNode(
                            next,
                            newCost,
                            newCost + heuristic(next, target)
                        )
                    )
                }
            }
        }
    }

    if (reached == null) return null

    var step = reached

    while (parent[step] != null && parent[step] != start) {
        step = parent[step]!!
    }

    return if (step == start) null else step
}

on<ClientTickEvent> {
    if (!inGame || !player.alive() || game.screenOpen()) {
        targetId = -1
        nextNode = null
        return@on
    }

    if (inventory.full()) {
        targetId = -1
        nextNode = null
        return@on
    }

    val pos = player.position()

    val target = world.entitiesNear(
        pos,
        range.toDouble(),
        filters.item()
    )
        .filter { it.alive() }
        .minByOrNull { it.position().distanceTo(pos) }

    if (target == null) {
        targetId = -1
        nextNode = null
        return@on
    }

    if (targetId != target.id()) {
        targetId = target.id()
        nextNode = null
        lastPathTick = -100L
    }

    val step = nextNode
    var reachedStep = false

    if (step != null) {
        val dx = step.x + 0.5 - pos.x()
        val dz = step.z + 0.5 - pos.z()

        reachedStep =
            kotlin.math.sqrt(dx * dx + dz * dz) < 0.35 &&
            kotlin.math.abs(step.y - floorInt(pos.y())) <= 1
    }

    if (nextNode == null ||
        reachedStep ||
        client.tick() - lastPathTick >= 20
    ) {
        lastPathTick = client.tick()

        val start = Node(
            floorInt(pos.x()),
            floorInt(pos.y()),
            floorInt(pos.z())
        )

        val targetPos = target.position()

        val goal = Node(
            floorInt(targetPos.x()),
            floorInt(targetPos.y()),
            floorInt(targetPos.z())
        )

        nextNode = findPath(start, goal)
    }

    if (nextNode == null) {
        val targetPos = target.position()
        val dx = targetPos.x() - pos.x()
        val dz = targetPos.z() - pos.z()
        val distance = kotlin.math.sqrt(dx * dx + dz * dz)

        if (distance > 0.1) {
            nextNode = Node(
                floorInt(pos.x() + dx / distance * 3.0),
                floorInt(pos.y()),
                floorInt(pos.z() + dz / distance * 3.0)
            )
        }
    }
}

on<MoveInputEvent> { e ->
    if (!inGame ||
        !player.alive() ||
        game.screenOpen() ||
        inventory.full() ||
        targetId < 0
    ) {
        e.forward(false)
        e.backward(false)
        e.left(false)
        e.right(false)
        e.jump(false)
        return@on
    }

    val target = world.entityById(targetId)
    val step = nextNode

    if (target == null || !target.alive() || step == null) {
        e.forward(false)
        e.backward(false)
        e.left(false)
        e.right(false)
        e.jump(false)
        return@on
    }

    val pos = player.position()
    val px = floorInt(pos.x())
    val py = floorInt(pos.y())
    val pz = floorInt(pos.z())

    val feet = world.block(px, py, pz)
    val head = world.block(px, py + 1, pz)

    val inWater =
        feet.fluid()?.id()?.contains("water") == true ||
        head.fluid()?.id()?.contains("water") == true

    val above = world.block(px, py + 1, pz)
    val aboveHead = world.block(px, py + 2, pz)

    val canClimbOut =
        inWater &&
        (above.blocksMovement() || aboveHead.blocksMovement())

    val dx = step.x + 0.5 - pos.x()
    val dz = step.z + 0.5 - pos.z()
    val distance = kotlin.math.sqrt(dx * dx + dz * dz)

    val wantedYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz))

    if (rotateCamera && distance > 0.12) {
        val currentYaw = player.yaw().toDouble()
        val difference = normalizeAngle(wantedYaw - currentYaw)

        player.yaw(
            (currentYaw + difference.coerceIn(
                -turnSpeed.toDouble(),
                turnSpeed.toDouble()
            )).toFloat()
        )
    }

    val yaw = Math.toRadians(player.yaw().toDouble())
    val fx = -kotlin.math.sin(yaw)
    val fz = kotlin.math.cos(yaw)

    val forward = dx * fx + dz * fz
    val side = dx * -fz + dz * fx

    if (rotateCamera) {
        val angleDifference = kotlin.math.abs(
            normalizeAngle(wantedYaw - player.yaw().toDouble())
        )

        e.forward(distance > 0.12 && angleDifference < 35.0)
        e.backward(false)
        e.left(false)
        e.right(false)
    } else {
        e.forward(forward > 0.08)
        e.backward(forward < -0.08)
        e.right(side > 0.25)
        e.left(side < -0.25)
    }

    e.jump(
        canClimbOut ||
        (
            step.y > py &&
            step.y - py <= maxJumpHeight.toInt()
        )
    )
}
