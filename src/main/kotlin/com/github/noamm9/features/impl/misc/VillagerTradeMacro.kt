package com.github.noamm9.features.impl.misc

import com.github.noamm9.event.impl.KeyboardEvent
import com.github.noamm9.event.impl.ServerEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.network.PacketUtils.send
import com.github.noamm9.utils.render.Render2D
import com.github.noamm9.utils.render.Render2D.width
import com.github.noamm9.utils.world.WorldUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.MerchantMenu
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object VillagerTradeMacro: Feature("Trades string to villagers for emeralds, crafts the emeralds into emerald blocks and stores them in a nearby chest.") {
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Starts or stops the macro. Pressing Escape also stops it.")

    private val reach by SliderSetting("Reach", 4.5, 3.0, 5.5, 0.1)
        .withDescription("Max distance (in blocks, from your eyes) to the villagers, the crafting table and the chest. Stand still somewhere they are all in reach.")

    private val clickDelay by SliderSetting("Click Delay", 2, 1, 10, 1)
        .withDescription("Ticks to wait between trades and inventory clicks.")

    private val rotationTime by SliderSetting<Long>("Rotation Time", 150, 0, 500, 10)
        .withDescription("Time (ms) spent turning towards a villager or block before using it. &eSet to 0 to snap instantly.")

    private val restockDelay by SliderSetting("Restock Check", 60, 10, 600, 10)
        .withDescription("Seconds to wait before checking a sold out villager again.")

    private enum class State(val label: String) {
        FIND_VILLAGER("Looking for a villager"),
        OPEN_VILLAGER("Opening villager"),
        TRADING("Trading"),
        OPEN_CRAFTING("Opening crafting table"),
        CRAFTING("Crafting emerald blocks"),
        OPEN_CHEST("Opening chest"),
        STORING("Storing emerald blocks"),
        WAITING("Waiting for restock")
    }

    private val MENU_STATES = setOf(State.TRADING, State.CRAFTING, State.STORING)

    private const val OPEN_TIMEOUT = 40
    private const val MAX_OPEN_ATTEMPTS = 3
    private const val MAX_STALLS = 3
    private const val MAX_STATE_STEPS = 2000
    private const val VILLAGER_RETRY_MS = 30_000L

    private const val TRADE_RESULT_SLOT = 2
    private const val CRAFT_RESULT_SLOT = 0
    private val CRAFT_GRID_SLOTS = 1 .. 9
    private val CRAFT_INV_SLOTS = 10 .. 45

    private var running = false
    private var state = State.FIND_VILLAGER
    private var waitTicks = 0
    private var stateTicks = 0
    private var interactSent = false
    private var openAttempts = 0
    private var rotationJob: Job? = null

    private var villager: Entity? = null
    private var craftingTable: BlockPos? = null
    private var chest: BlockPos? = null
    private val villagerCooldowns = mutableMapOf<UUID, Long>()
    private val ignoredVillagers = mutableSetOf<UUID>()
    private var cheapestTrade: Int? = null

    private var tradeSelected = false
    private var usesAtClick = - 1
    private var tradeStalls = 0

    private var gridTarget = 0
    private var pendingGridSlot = - 1
    private var pickupSource = CRAFT_INV_SLOTS.first
    private var lastCraftTotal = - 1
    private var craftStalls = 0

    private var tradesDone = 0
    private var blocksStored = 0

    private val statusHud = hudElement("Villager Trade Macro", shouldDraw = { running }) { ctx, example ->
        val lines = if (example) listOf(
            "&bVillager Macro &7- &fTrading",
            "&7Emeralds: &a128 &7String: &f640",
            "&7Trades: &f32 &7Blocks stored: &a64"
        )
        else statusLines()

        val lineHeight = mc.font.lineHeight + 1
        lines.forEachIndexed { i, line -> Render2D.drawString(ctx, line, 0, i * lineHeight) }
        return@hudElement lines.maxOf { it.width() }.toFloat() to (lines.size * lineHeight).toFloat()
    }

    override fun init() {
        register<TickEvent.Start> {
            val screen = mc.screen
            if (toggleKey.isPressed() && (screen == null || screen is AbstractContainerScreen<*>)) {
                if (running) stop("&cStopped.", closeMenu = true)
                else start()
            }

            if (running) tick()
        }

        register<KeyboardEvent.KeyPressed> {
            if (running && event.keyEvent.key == GLFW.GLFW_KEY_ESCAPE) stop("&cStopped.")
        }

        register<WorldChangeEvent> { stop("&cStopped because you changed worlds.") }
        register<ServerEvent.Disconnect> { stop(null) }
    }

    override fun onDisable() {
        super.onDisable()
        stop(null)
    }

    private fun start() {
        if (mc.player == null) return
        if (villagersInReach().isEmpty()) return modMessage("&bVillager Macro: &cNo villagers within reach!")
        locateStations()?.let { return modMessage("&bVillager Macro: &c$it") }

        villagerCooldowns.clear()
        ignoredVillagers.clear()
        cheapestTrade = null
        villager = null
        pendingGridSlot = - 1
        tradesDone = 0
        blocksStored = 0
        running = true
        setState(State.FIND_VILLAGER)
        modMessage("&bVillager Macro: &aStarted. &7Press the toggle key or Escape to stop.")
    }

    private fun stop(message: String?, closeMenu: Boolean = false) {
        if (! running) return
        running = false
        rotationJob?.cancel()
        rotationJob = null
        villager = null

        if (closeMenu && state in MENU_STATES) {
            mc.player?.takeIf { it.containerMenu !== it.inventoryMenu }?.closeContainer()
        }

        message?.let { modMessage("&bVillager Macro: $it") }
    }

    private fun tick() {
        val player = mc.player ?: return stop(null)
        if (player.isDeadOrDying) return stop("&cStopped because you died.")
        // Singleplayer pauses the server, nothing we send would be answered.
        if (mc.isPaused) return
        if (waitTicks > 0) {
            waitTicks --
            return
        }

        stateTicks ++
        if (stateTicks > MAX_STATE_STEPS && state in MENU_STATES) return stop("&cStopped, got stuck while ${state.label.lowercase()}.")

        when (state) {
            State.FIND_VILLAGER -> findNextAction()
            State.OPEN_VILLAGER -> openVillager()
            State.TRADING -> trade()
            State.OPEN_CRAFTING -> openBlock(craftingTable, "crafting table", State.CRAFTING) { it is CraftingMenu }
            State.CRAFTING -> craft()
            State.OPEN_CHEST -> openBlock(chest, "chest", State.STORING) { it is ChestMenu }
            State.STORING -> store()
            State.WAITING -> if (nextVillager() != null) setState(State.FIND_VILLAGER)
        }
    }

    private fun setState(newState: State, delay: Int = 0) {
        state = newState
        waitTicks = delay
        stateTicks = 0
        interactSent = false
        openAttempts = 0
        rotationJob?.cancel()
        rotationJob = null
    }

    private fun findNextAction() {
        val emeralds = countItem(Items.EMERALD)
        val inventoryFull = ! canFit(Items.EMERALD)
        val outOfString = countItem(Items.STRING) < (cheapestTrade ?: 1)

        if (inventoryFull || outOfString) {
            if (emeralds >= 9) return startCrafting()
            if (inventoryFull && countItem(Items.EMERALD_BLOCK) > 0) return startStoring()
            return stop(if (outOfString) "&eOut of string, stopping." else "&cYour inventory is full!")
        }

        nextVillager()?.let {
            villager = it
            return setState(State.OPEN_VILLAGER)
        }

        // Every villager is sold out, store what we have while waiting for them to restock.
        if (emeralds >= 9) return startCrafting()
        if (villagersInReach().all { it.uuid in ignoredVillagers }) return stop("&cNo villagers with a string trade within reach!")
        setState(State.WAITING)
    }

    private fun startCrafting() {
        if (! stationsValid()) locateStations()?.let { return stop("&c$it") }
        setState(State.OPEN_CRAFTING)
    }

    private fun startStoring() {
        if (! stationsValid()) locateStations()?.let { return stop("&c$it") }
        setState(State.OPEN_CHEST)
    }

    private fun openVillager() {
        val target = villager
        if (target == null || ! target.isAlive || ! inReach(target.boundingBox)) return setState(State.FIND_VILLAGER)

        if (interactSent && mc.player !!.containerMenu is MerchantMenu) {
            tradeSelected = false
            usesAtClick = - 1
            tradeStalls = 0
            return setState(State.TRADING, clickDelay.value)
        }

        if (! interactSent) {
            if (! clearScreen()) return
            lookThen(target.boundingBox.center) { PlayerUtils.interactEntity(target, InteractionHand.MAIN_HAND) }
        }
        else if (stateTicks > OPEN_TIMEOUT) {
            // Sleeping, jobless or busy villagers never open the trade menu, try them again later.
            villagerCooldowns[target.uuid] = System.currentTimeMillis() + VILLAGER_RETRY_MS
            setState(State.FIND_VILLAGER)
        }
    }

    private fun trade() {
        val menu = mc.player !!.containerMenu as? MerchantMenu ?: return setState(State.FIND_VILLAGER, clickDelay.value)
        val target = villager ?: return closeMenu(State.FIND_VILLAGER)
        val now = System.currentTimeMillis()

        val offers = menu.offers
        if (offers.isEmpty()) {
            if (stateTicks > OPEN_TIMEOUT) {
                villagerCooldowns[target.uuid] = now + VILLAGER_RETRY_MS
                closeMenu(State.FIND_VILLAGER)
            }
            return
        }

        val index = offers.indexOfFirst(::isStringTrade)
        if (index == - 1) {
            ignoredVillagers.add(target.uuid)
            return closeMenu(State.FIND_VILLAGER)
        }

        val offer = offers[index]
        val cost = offer.costA.count
        cheapestTrade = min(cheapestTrade ?: cost, cost)

        if (! tradeSelected && usesAtClick >= 0) {
            val gained = offer.uses - usesAtClick
            usesAtClick = - 1
            if (gained > 0) {
                tradesDone += gained
                tradeStalls = 0
            }
            else if (++ tradeStalls >= MAX_STALLS) {
                villagerCooldowns[target.uuid] = now + VILLAGER_RETRY_MS
                return closeMenu(State.FIND_VILLAGER)
            }
        }

        when {
            offer.isOutOfStock || stringIn(menu) < cost -> {
                villagerCooldowns[target.uuid] = now + restockDelay.value * 1000L
                closeMenu(State.FIND_VILLAGER)
            }

            ! canFit(Items.EMERALD) -> closeMenu(State.FIND_VILLAGER)

            // Same as clicking the trade in the list: pulls the string into the payment slot.
            ! tradeSelected -> {
                menu.setSelectionHint(index)
                menu.tryMoveItems(index)
                ServerboundSelectTradePacket(index).send()
                tradeSelected = true
                waitTicks = clickDelay.value
            }

            else -> {
                usesAtClick = offer.uses
                tradeSelected = false
                click(TRADE_RESULT_SLOT, 0, ClickType.QUICK_MOVE)
            }
        }
    }

    /**
     * Fills every grid slot with the same amount of emeralds, then shift clicks the result.
     * Repeats until less than 9 emeralds are left. Does one click per call.
     */
    private fun craft() {
        val menu = mc.player !!.containerMenu as? CraftingMenu ?: return setState(State.OPEN_CRAFTING, clickDelay.value)

        val carried = menu.carried
        if (! carried.isEmpty) return placeCarried(menu, carried)

        val sources = CRAFT_INV_SLOTS.filter { isPlain(menu.getSlot(it).item, Items.EMERALD) }
        val total = sources.sumOf { menu.getSlot(it).item.count } + CRAFT_GRID_SLOTS.sumOf { menu.getSlot(it).item.count }
        val perSlot = min(64, total / 9)

        if (perSlot == 0) {
            CRAFT_GRID_SLOTS.firstOrNull { menu.getSlot(it).hasItem() }?.let { return click(it, 0, ClickType.QUICK_MOVE) }
            pendingGridSlot = - 1
            return closeMenu(if (countItem(Items.EMERALD_BLOCK) > 0) State.OPEN_CHEST else State.FIND_VILLAGER)
        }

        val gridSlot = CRAFT_GRID_SLOTS.firstOrNull { menu.getSlot(it).item.count < perSlot }
        if (gridSlot == null) {
            if (total != lastCraftTotal) craftStalls = 0
            else if (++ craftStalls >= MAX_STALLS) return stop("&cCouldn't craft emerald blocks, is your inventory full?", closeMenu = true)
            lastCraftTotal = total
            return click(CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE)
        }

        val need = perSlot - menu.getSlot(gridSlot).item.count
        val source = sources.firstOrNull { menu.getSlot(it).item.count == need }
            ?: sources.maxByOrNull { menu.getSlot(it).item.count }
            ?: return stop("&cLost track of the emeralds while crafting.", closeMenu = true)

        gridTarget = perSlot
        pendingGridSlot = gridSlot
        pickupSource = source
        click(source, 0, ClickType.PICKUP)
    }

    private fun placeCarried(menu: CraftingMenu, carried: ItemStack) {
        val gridSlot = pendingGridSlot
        if (gridSlot in CRAFT_GRID_SLOTS && isPlain(carried, Items.EMERALD)) {
            val need = gridTarget - menu.getSlot(gridSlot).item.count
            val surplus = carried.count - need
            val source = menu.getSlot(pickupSource).item
            val canPutBack = source.isEmpty || (isPlain(source, Items.EMERALD) && source.count < source.maxStackSize)

            // Left click drops the whole stack, right click drops a single emerald.
            // Whichever is fewer clicks: drop emeralds into the grid one by one, or put the surplus back first.
            if (need > 0) return when {
                surplus <= 0 -> click(gridSlot, 0, ClickType.PICKUP)
                need <= surplus || ! canPutBack -> click(gridSlot, 1, ClickType.PICKUP)
                else -> click(pickupSource, 1, ClickType.PICKUP)
            }
        }

        pendingGridSlot = - 1
        val target = CRAFT_INV_SLOTS.firstOrNull { ! menu.getSlot(it).hasItem() }
            ?: CRAFT_INV_SLOTS.firstOrNull {
                val stack = menu.getSlot(it).item
                ItemStack.isSameItemSameComponents(stack, carried) && stack.count < stack.maxStackSize
            }
            ?: return stop("&cNo room to put the emeralds back in your inventory.", closeMenu = true)

        click(target, 0, ClickType.PICKUP)
    }

    private fun store() {
        val menu = mc.player !!.containerMenu as? ChestMenu ?: return setState(State.OPEN_CHEST, clickDelay.value)
        val chestSize = menu.rowCount * 9

        val blockSlot = (chestSize until menu.slots.size).firstOrNull { isPlain(menu.getSlot(it).item, Items.EMERALD_BLOCK) }
            ?: return closeMenu(State.FIND_VILLAGER)

        val hasRoom = (0 until chestSize).any {
            val stack = menu.getSlot(it).item
            stack.isEmpty || (isPlain(stack, Items.EMERALD_BLOCK) && stack.count < stack.maxStackSize)
        }
        if (! hasRoom) return stop("&cThe chest is full!", closeMenu = true)

        val before = menu.getSlot(blockSlot).item.count
        click(blockSlot, 0, ClickType.QUICK_MOVE)
        blocksStored += before - menu.getSlot(blockSlot).item.count
    }

    /** Opens the block at [pos] and switches to [next] once a menu matching [isMenu] is open. */
    private fun openBlock(pos: BlockPos?, name: String, next: State, isMenu: (AbstractContainerMenu) -> Boolean) {
        val player = mc.player ?: return
        if (pos == null) return stop("&cLost track of the $name.")
        if (interactSent && isMenu(player.containerMenu)) return setState(next, clickDelay.value)

        if (! interactSent) {
            if (! clearScreen()) return
            // Sneaking would place the held item instead of opening the block.
            if (player.isShiftKeyDown) return
            lookThen(blockHitResult(pos, false).location) {
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, blockHitResult(pos, true))
            }
        }
        else if (stateTicks > OPEN_TIMEOUT) {
            if (++ openAttempts >= MAX_OPEN_ATTEMPTS) return stop("&cCouldn't open the $name.")
            interactSent = false
            stateTicks = 0
        }
    }

    /** Closes leftover menus before using a villager or block. Returns true once nothing is in the way. */
    private fun clearScreen(): Boolean {
        when (mc.screen) {
            // The game pauses itself when it loses focus, that shouldn't stall the macro.
            null, is PauseScreen -> return true
            is AbstractContainerScreen<*> -> {
                mc.player?.closeContainer()
                waitTicks = clickDelay.value
            }
        }
        return false
    }

    private fun closeMenu(next: State) {
        mc.player?.closeContainer()
        setState(next, clickDelay.value)
    }

    /** Turns towards [target], then runs [action] once the rotation is done. */
    private fun lookThen(target: Vec3, action: () -> Unit) {
        val job = rotationJob
        if (job == null) {
            rotationJob = scope.launch { PlayerUtils.rotateSmoothly(target, rotationTime.value) }
            return
        }
        if (! job.isCompleted) return

        rotationJob = null
        action()
        interactSent = true
        stateTicks = 0
    }

    private fun click(slot: Int, button: Int, type: ClickType) {
        val player = mc.player ?: return
        mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, slot, button, type, player)
        waitTicks = clickDelay.value
    }

    /** Prefers what the crosshair is on, otherwise hits the center of the face pointing at the player. */
    private fun blockHitResult(pos: BlockPos, useCrosshair: Boolean): BlockHitResult {
        val crosshair = mc.hitResult
        if (useCrosshair && crosshair is BlockHitResult && crosshair.type == HitResult.Type.BLOCK && crosshair.blockPos == pos) return crosshair

        val eye = mc.player !!.eyePosition
        val center = Vec3.atCenterOf(pos)
        val dx = eye.x - center.x
        val dy = eye.y - center.y
        val dz = eye.z - center.z

        val face = when {
            abs(dx) < 0.5 && abs(dz) < 0.5 -> if (dy > 0) Direction.UP else Direction.DOWN
            abs(dx) >= abs(dz) -> if (dx > 0) Direction.EAST else Direction.WEST
            else -> if (dz > 0) Direction.SOUTH else Direction.NORTH
        }

        val hit = center.add(face.stepX * 0.5, face.stepY * 0.5, face.stepZ * 0.5)
        return BlockHitResult(hit, face, pos, false)
    }

    /** Finds the crafting table (preferring one right under a chest) and the chest. Returns an error message if one is missing. */
    private fun locateStations(): String? {
        val eye = mc.player?.eyePosition ?: return "You are not in a world!"
        val radius = ceil(reach.value).toInt()
        val origin = BlockPos.containing(eye)
        val tables = mutableListOf<BlockPos>()
        val chests = mutableListOf<BlockPos>()

        for (pos in BlockPos.betweenClosed(origin.offset(- radius, - radius, - radius), origin.offset(radius, radius, radius))) {
            if (! inReach(AABB(pos))) continue
            if (WorldUtils.getBlockAt(pos) == Blocks.CRAFTING_TABLE) tables.add(pos.immutable())
            else if (isChest(pos)) chests.add(pos.immutable())
        }

        val byDistance = compareBy<BlockPos> { distanceSq(AABB(it), eye) }
        val table = tables.filter { it.above() in chests }.minWithOrNull(byDistance)
            ?: tables.minWithOrNull(byDistance)
            ?: return "No crafting table within reach!"
        val chestPos = table.above().takeIf { it in chests }
            ?: chests.minWithOrNull(byDistance)
            ?: return "No chest within reach!"

        craftingTable = table
        chest = chestPos
        return null
    }

    private fun stationsValid(): Boolean {
        val table = craftingTable ?: return false
        val chestPos = chest ?: return false
        return WorldUtils.getBlockAt(table) == Blocks.CRAFTING_TABLE && isChest(chestPos) && inReach(AABB(table)) && inReach(AABB(chestPos))
    }

    private fun villagersInReach(): List<Entity> {
        val level = mc.level ?: return emptyList()
        val eye = mc.player?.eyePosition ?: return emptyList()

        return level.entitiesForRendering().filter {
            it.type == EntityType.VILLAGER && it.isAlive && (it as? LivingEntity)?.isBaby != true && inReach(it.boundingBox, eye)
        }.sortedBy { distanceSq(it.boundingBox, eye) }
    }

    private fun nextVillager(): Entity? {
        val now = System.currentTimeMillis()
        return villagersInReach().firstOrNull { it.uuid !in ignoredVillagers && (villagerCooldowns[it.uuid] ?: 0L) <= now }
    }

    private fun statusLines() = buildList {
        add("&bVillager Macro &7- &f${state.label}")
        add("&7Emeralds: &a${countItem(Items.EMERALD)} &7String: &f${countItem(Items.STRING)}")
        add("&7Trades: &f$tradesDone &7Blocks stored: &a$blocksStored")

        if (state == State.WAITING) {
            val now = System.currentTimeMillis()
            villagersInReach().filter { it.uuid !in ignoredVillagers }.minOfOrNull { villagerCooldowns[it.uuid] ?: now }?.let {
                add("&7Next check in &f${max(0L, (it - now + 999) / 1000)}s")
            }
        }
    }

    private fun isStringTrade(offer: MerchantOffer) = offer.costA.`is`(Items.STRING) && offer.costB.isEmpty && offer.result.`is`(Items.EMERALD)

    private fun stringIn(menu: MerchantMenu) = menu.slots.withIndex().sumOf { (index, slot) ->
        if (index != TRADE_RESULT_SLOT && isPlain(slot.item, Items.STRING)) slot.item.count else 0
    }

    private fun isChest(pos: BlockPos) = WorldUtils.getBlockAt(pos).let { it == Blocks.CHEST || it == Blocks.TRAPPED_CHEST }

    /** Renamed or enchanted items won't stack with plain ones, so they are left alone. */
    private fun isPlain(stack: ItemStack, item: Item) = ! stack.isEmpty && ItemStack.isSameItemSameComponents(stack, item.defaultInstance)

    private fun countItem(item: Item) = mc.player?.inventory?.nonEquipmentItems?.sumOf { if (isPlain(it, item)) it.count else 0 } ?: 0

    private fun canFit(item: Item) = mc.player?.inventory?.nonEquipmentItems?.any {
        it.isEmpty || (isPlain(it, item) && it.count < it.maxStackSize)
    } == true

    private fun inReach(box: AABB, eye: Vec3? = mc.player?.eyePosition): Boolean {
        if (eye == null) return false
        return distanceSq(box, eye) <= reach.value * reach.value
    }

    private fun distanceSq(box: AABB, point: Vec3): Double {
        val dx = max(max(box.minX - point.x, point.x - box.maxX), 0.0)
        val dy = max(max(box.minY - point.y, point.y - box.maxY), 0.0)
        val dz = max(max(box.minZ - point.z, point.z - box.maxZ), 0.0)
        return dx * dx + dy * dy + dz * dz
    }
}
