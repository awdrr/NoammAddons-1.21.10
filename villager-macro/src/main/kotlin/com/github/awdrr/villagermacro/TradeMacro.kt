package com.github.awdrr.villagermacro

import com.github.awdrr.villagermacro.VillagerMacroMod.chat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket
import net.minecraft.util.Mth
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
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.*

/**
 * Trades string to villagers for emeralds, running the string command (/string) whenever it runs out.
 * Once the inventory has no room left for emeralds it crafts them into blocks at the crafting table
 * under the chest, stores the blocks in the chest and goes back to trading.
 * When every villager is sold out it waits for them to restock.
 */
object TradeMacro {
    private val mc get() = Minecraft.getInstance()

    private enum class State(val label: String) {
        FIND_VILLAGER("Looking for a villager"),
        GET_STRING("Getting string"),
        STRING_COOLDOWN("Waiting to get string"),
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

    private const val STRING_TIMEOUT = 100
    private const val STRING_RETRY_MS = 30_000L
    private const val MIN_STRING_INTERVAL_MS = 2_000L
    private const val MAX_STRING_FAILS = 10

    private const val RECIPE_TIMEOUT = 20
    private const val MIN_RECIPE_INTERVAL_MS = 250L

    private const val PAYMENT_SLOT = 0
    private val TRADE_INV_SLOTS = 3 .. 38
    private const val TRADE_RESULT_SLOT = 2
    private const val CRAFT_RESULT_SLOT = 0
    private val CRAFT_GRID_SLOTS = 1 .. 9
    private val CRAFT_INV_SLOTS = 10 .. 45

    private class Rotation(val fromYaw: Float, val fromPitch: Float, val toYaw: Float, val toPitch: Float, val ticks: Int) {
        var elapsed = 0
    }

    var running = false
        private set
    private var state = State.FIND_VILLAGER
    private var waitTicks = 0
    private var stateTicks = 0
    private var interactSent = false
    private var openAttempts = 0
    private var rotation: Rotation? = null
    private var level: ClientLevel? = null

    private var villager: Entity? = null
    private var craftingTable: BlockPos? = null
    private var chest: BlockPos? = null
    private val villagerCooldowns = mutableMapOf<UUID, Long>()
    private val ignoredVillagers = mutableSetOf<UUID>()
    private var cheapestTrade: Int? = null

    private var usesAtClick = - 1
    private var tradeStalls = 0
    private var needsCrafting = false

    private var stringBefore = 0
    private var stringFails = 0
    private var lastStringCommand = 0L
    private var nextStringAttempt = 0L

    private var gridTarget = 0
    private var pendingGridSlot = - 1
    private var pickupSource = CRAFT_INV_SLOTS.first
    private var lastCraftTotal = - 1
    private var craftStalls = 0
    private var useRecipeBook = true
    private var recipePending = false
    private var recipePlacedAt = 0
    private var lastRecipePlace = 0L

    private var tradesDone = 0
    private var blocksStored = 0
    private var statusTicks = 0

    fun toggle() = if (running) stop("§cStopped.", closeMenu = true) else start()

    fun start() {
        if (running) return
        if (mc.player == null) return
        if (villagersInReach().isEmpty()) return chat("§cNo villagers within reach!")
        locateStations()?.let { return chat("§c$it") }

        villagerCooldowns.clear()
        ignoredVillagers.clear()
        cheapestTrade = null
        villager = null
        pendingGridSlot = - 1
        needsCrafting = false
        stringFails = 0
        tradesDone = 0
        blocksStored = 0
        level = mc.level
        running = true
        setState(State.FIND_VILLAGER)
        chat("§aStarted. §7Press the toggle key or Escape to stop.")
    }

    fun stop(message: String?, closeMenu: Boolean = false) {
        if (! running) return
        running = false
        rotation = null
        villager = null

        if (closeMenu && state in MENU_STATES) {
            mc.player?.takeIf { it.containerMenu !== it.inventoryMenu }?.closeContainer()
        }

        mc.gui.setOverlayMessage(Component.empty(), false)
        message?.let { chat(it) }
    }

    fun onTick() {
        if (! running) return
        val player = mc.player ?: return stop(null)
        if (mc.level !== level) return stop("§cStopped because you changed worlds.")
        if (player.isDeadOrDying) return stop("§cStopped because you died.")
        // Singleplayer pauses the server, nothing we send would be answered.
        if (mc.isPaused) return

        if (++ statusTicks >= 10) {
            statusTicks = 0
            mc.gui.setOverlayMessage(Component.literal(statusLine()), false)
        }

        if (waitTicks > 0) {
            waitTicks --
            return
        }

        stateTicks ++
        if (stateTicks > MAX_STATE_STEPS && state in MENU_STATES) return stop("§cStopped, got stuck while ${state.label.lowercase()}.")

        when (state) {
            State.FIND_VILLAGER -> findNextAction()
            State.GET_STRING -> getString()
            State.STRING_COOLDOWN -> if (System.currentTimeMillis() >= nextStringAttempt) setState(State.FIND_VILLAGER)
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
        rotation = null
    }

    private fun findNextAction() {
        val hasString = stringCount() >= (cheapestTrade ?: 1)

        // String in the inventory doesn't count as "full": trading pulls it into the payment slot and frees space.
        // needsCrafting is set by trade() when there's still no room for an emerald after that.
        val inventoryFull = needsCrafting || (! hasString && (! canFit(Items.EMERALD) || ! canFit(Items.STRING)))
        if (inventoryFull) {
            needsCrafting = false
            if (countItem(Items.EMERALD) >= 9) return startCrafting()
            if (countItem(Items.EMERALD_BLOCK) > 0) return startStoring()
            return stop("§cYour inventory is full!")
        }

        if (! hasString) {
            // /string only fills empty slots, so with just a few left it barely gives anything.
            // Turn the emeralds into blocks and store them first to make room for a full refill.
            if (emptySlots() <= MacroConfig.craftAtFreeSlots && countItem(Items.EMERALD) >= 9) return startCrafting()
            return setState(State.GET_STRING)
        }

        nextVillager()?.let {
            villager = it
            return setState(State.OPEN_VILLAGER)
        }

        if (villagersInReach().all { it.uuid in ignoredVillagers }) return stop("§cNo villagers with a string trade within reach!")
        setState(State.WAITING)
    }

    /** Runs the string command and waits for string to show up in the inventory, retrying later if it doesn't. */
    private fun getString() {
        val now = System.currentTimeMillis()
        val command = MacroConfig.stringCommand

        if (! interactSent) {
            if (! clearScreen()) return
            if (now - lastStringCommand < MIN_STRING_INTERVAL_MS) return
            stringBefore = stringCount()
            mc.connection?.sendCommand(command)
            lastStringCommand = now
            interactSent = true
            stateTicks = 0
            return
        }

        if (stringCount() > stringBefore) {
            stringFails = 0
            // Give the rest of the string a moment to arrive.
            return setState(State.FIND_VILLAGER, 10)
        }

        if (stateTicks > STRING_TIMEOUT) {
            if (++ stringFails >= MAX_STRING_FAILS) return stop("§c/$command didn't give you any string $MAX_STRING_FAILS times in a row.")
            if (stringFails == 1) chat("§e/$command didn't give you any string, trying again every ${STRING_RETRY_MS / 1000}s.")
            nextStringAttempt = now + STRING_RETRY_MS
            setState(State.STRING_COOLDOWN)
        }
    }

    private fun startCrafting() {
        if (! stationsValid()) locateStations()?.let { return stop("§c$it") }
        useRecipeBook = true
        recipePending = false
        lastCraftTotal = - 1
        craftStalls = 0
        setState(State.OPEN_CRAFTING)
    }

    private fun startStoring() {
        if (! stationsValid()) locateStations()?.let { return stop("§c$it") }
        setState(State.OPEN_CHEST)
    }

    private fun openVillager() {
        val target = villager
        if (target == null || ! target.isAlive || ! inReach(target.boundingBox)) return setState(State.FIND_VILLAGER)

        if (interactSent && mc.player !!.containerMenu is MerchantMenu) {
            usesAtClick = - 1
            tradeStalls = 0
            return setState(State.TRADING, MacroConfig.clickDelay)
        }

        if (! interactSent) {
            if (! clearScreen()) return
            lookThen(target.boundingBox.center) { interactEntity(target) }
        }
        else if (stateTicks > OPEN_TIMEOUT) {
            // Sleeping, jobless or busy villagers never open the trade menu, try them again later.
            villagerCooldowns[target.uuid] = System.currentTimeMillis() + VILLAGER_RETRY_MS
            setState(State.FIND_VILLAGER)
        }
    }

    private fun trade() {
        val menu = mc.player !!.containerMenu as? MerchantMenu ?: return setState(State.FIND_VILLAGER, MacroConfig.clickDelay)
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

        if (usesAtClick >= 0) {
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

        val carried = menu.carried
        if (! carried.isEmpty) return placeCarriedString(menu, carried, menu.getSlot(PAYMENT_SLOT).item)

        if (offer.isOutOfStock) {
            villagerCooldowns[target.uuid] = now + MacroConfig.restockDelay * 1000L
            return closeMenu(State.FIND_VILLAGER)
        }

        if (paidString(menu) < cost) {
            // Same as pressing space on the selected trade: picks it again, which refills the payment slot with string.
            menu.setSelectionHint(index)
            menu.tryMoveItems(index)
            mc.connection?.send(ServerboundSelectTradePacket(index))

            if (paidString(menu) < cost) {
                val available = stringIn(menu)
                if (available < cost) {
                    // Can't afford this villager. If a cheaper one exists use it, otherwise go get more string.
                    if (available >= (cheapestTrade ?: cost)) villagerCooldowns[target.uuid] = now + VILLAGER_RETRY_MS
                    return closeMenu(State.FIND_VILLAGER)
                }

                // The game couldn't refill it (no room to take the leftover string back first),
                // so top it up by hand: pick up a stack of string here, drop it on the payment slot next step.
                val payment = menu.getSlot(PAYMENT_SLOT).item
                val source = TRADE_INV_SLOTS.filter {
                    val stack = menu.getSlot(it).item
                    stack.`is`(Items.STRING) && (payment.isEmpty || ItemStack.isSameItemSameComponents(stack, payment))
                }.maxByOrNull { menu.getSlot(it).item.count } ?: return closeMenu(State.FIND_VILLAGER)

                pickupSource = source
                return click(source, 0, ClickType.PICKUP)
            }
        }

        // No room for the emerald even with the string moved into the payment slot.
        if (! canFit(Items.EMERALD)) {
            needsCrafting = true
            return closeMenu(State.FIND_VILLAGER)
        }

        // Shift click the output in the same tick as the refill. It keeps trading until the payment slot runs low.
        usesAtClick = offer.uses
        click(TRADE_RESULT_SLOT, 0, ClickType.QUICK_MOVE)
    }

    private fun paidString(menu: MerchantMenu) = menu.getSlot(PAYMENT_SLOT).item.let { if (it.`is`(Items.STRING)) it.count else 0 }

    /** Drops picked up string onto the payment slot (it holds up to a stack), then puts whatever is left back. */
    private fun placeCarriedString(menu: MerchantMenu, carried: ItemStack, payment: ItemStack) {
        val paymentHasRoom = payment.isEmpty || (ItemStack.isSameItemSameComponents(payment, carried) && payment.count < payment.maxStackSize)
        if (carried.`is`(Items.STRING) && paymentHasRoom) return click(PAYMENT_SLOT, 0, ClickType.PICKUP)

        val target = pickupSource.takeIf { it in TRADE_INV_SLOTS && ! menu.getSlot(it).hasItem() }
            ?: TRADE_INV_SLOTS.firstOrNull { ! menu.getSlot(it).hasItem() }
            ?: TRADE_INV_SLOTS.firstOrNull {
                val stack = menu.getSlot(it).item
                ItemStack.isSameItemSameComponents(stack, carried) && stack.count < stack.maxStackSize
            }
            ?: return stop("§cNo room to put the string back in your inventory.", closeMenu = true)

        click(target, 0, ClickType.PICKUP)
    }

    private fun craft() {
        val menu = mc.player !!.containerMenu as? CraftingMenu ?: return setState(State.OPEN_CRAFTING, MacroConfig.clickDelay)

        val carried = menu.carried
        if (! carried.isEmpty) return placeCarried(menu, carried)

        if (useRecipeBook) craftWithRecipeBook(menu)
        else craftByHand(menu)
    }

    /**
     * Same as a player using the recipe book: shift click the emerald block recipe (fills the grid with as many
     * emeralds as fit, up to a stack per slot), then shift click the output. Two actions per 64 blocks.
     */
    private fun craftWithRecipeBook(menu: CraftingMenu) {
        val inGrid = CRAFT_GRID_SLOTS.sumOf { menu.getSlot(it).item.count }

        if (inGrid > 0 && isPlain(menu.getSlot(CRAFT_RESULT_SLOT).item, Items.EMERALD_BLOCK)) {
            recipePending = false
            return craftResult(countItem(Items.EMERALD) + inGrid)
        }

        if (recipePending) {
            // Wait for the server to fill the grid, fall back to crafting by hand if it never does.
            if (stateTicks - recipePlacedAt > RECIPE_TIMEOUT) {
                recipePending = false
                useRecipeBook = false
            }
            return
        }

        // Something in the grid the recipe book didn't put there, crafting by hand sorts it out.
        if (inGrid > 0) {
            useRecipeBook = false
            return
        }
        if (countItem(Items.EMERALD) < 9) return finishCrafting()

        val recipe = emeraldBlockRecipe()
        if (recipe == null) {
            useRecipeBook = false
            return
        }

        // Servers rate limit recipe book clicks (Paper allows 5 a second).
        val now = System.currentTimeMillis()
        if (now - lastRecipePlace < MIN_RECIPE_INTERVAL_MS) return
        mc.gameMode?.handlePlaceRecipe(menu.containerId, recipe, true)
        lastRecipePlace = now
        recipePending = true
        recipePlacedAt = stateTicks
    }

    /** The emerald block recipe from the recipe book, if it's unlocked (it is once you've had an emerald). */
    private fun emeraldBlockRecipe() = mc.level?.let { level ->
        val context = SlotDisplayContext.fromLevel(level)
        mc.player?.recipeBook?.collections?.asSequence()?.flatMap { it.recipes }
            ?.firstOrNull { entry -> entry.resultItems(context).any { isPlain(it, Items.EMERALD_BLOCK) } }
            ?.id()
    }

    private fun craftResult(total: Int) {
        if (total != lastCraftTotal) craftStalls = 0
        else if (++ craftStalls >= MAX_STALLS) return stop("§cCouldn't craft emerald blocks, is your inventory full?", closeMenu = true)
        lastCraftTotal = total
        click(CRAFT_RESULT_SLOT, 0, ClickType.QUICK_MOVE)
    }

    private fun finishCrafting() {
        pendingGridSlot = - 1
        closeMenu(if (countItem(Items.EMERALD_BLOCK) > 0) State.OPEN_CHEST else State.FIND_VILLAGER)
    }

    /**
     * Fallback when the recipe book can't be used: fills every grid slot with the same amount of emeralds
     * by clicking, then shift clicks the result. Repeats until less than 9 emeralds are left. One click per call.
     */
    private fun craftByHand(menu: CraftingMenu) {
        val sources = CRAFT_INV_SLOTS.filter { isPlain(menu.getSlot(it).item, Items.EMERALD) }
        val total = sources.sumOf { menu.getSlot(it).item.count } + CRAFT_GRID_SLOTS.sumOf { menu.getSlot(it).item.count }
        val perSlot = min(64, total / 9)

        if (perSlot == 0) {
            CRAFT_GRID_SLOTS.firstOrNull { menu.getSlot(it).hasItem() }?.let { return click(it, 0, ClickType.QUICK_MOVE) }
            return finishCrafting()
        }

        val gridSlot = CRAFT_GRID_SLOTS.firstOrNull { menu.getSlot(it).item.count < perSlot }
        if (gridSlot == null) return craftResult(total)

        val need = perSlot - menu.getSlot(gridSlot).item.count
        val source = sources.firstOrNull { menu.getSlot(it).item.count == need }
            ?: sources.maxByOrNull { menu.getSlot(it).item.count }
            ?: return stop("§cLost track of the emeralds while crafting.", closeMenu = true)

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
            ?: return stop("§cNo room to put the emeralds back in your inventory.", closeMenu = true)

        click(target, 0, ClickType.PICKUP)
    }

    private fun store() {
        val menu = mc.player !!.containerMenu as? ChestMenu ?: return setState(State.OPEN_CHEST, MacroConfig.clickDelay)
        val chestSize = menu.rowCount * 9

        val blockSlot = (chestSize until menu.slots.size).firstOrNull { isPlain(menu.getSlot(it).item, Items.EMERALD_BLOCK) }
            ?: return closeMenu(State.FIND_VILLAGER)

        val hasRoom = (0 until chestSize).any {
            val stack = menu.getSlot(it).item
            stack.isEmpty || (isPlain(stack, Items.EMERALD_BLOCK) && stack.count < stack.maxStackSize)
        }
        if (! hasRoom) return stop("§cThe chest is full!", closeMenu = true)

        val before = menu.getSlot(blockSlot).item.count
        click(blockSlot, 0, ClickType.QUICK_MOVE)
        blocksStored += before - menu.getSlot(blockSlot).item.count
    }

    /** Opens the block at [pos] and switches to [next] once a menu matching [isMenu] is open. */
    private fun openBlock(pos: BlockPos?, name: String, next: State, isMenu: (AbstractContainerMenu) -> Boolean) {
        val player = mc.player ?: return
        if (pos == null) return stop("§cLost track of the $name.")
        if (interactSent && isMenu(player.containerMenu)) return setState(next, MacroConfig.clickDelay)

        if (! interactSent) {
            if (! clearScreen()) return
            // Sneaking would place the held item instead of opening the block.
            if (player.isShiftKeyDown) return
            lookThen(blockHitResult(pos, false).location) {
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, blockHitResult(pos, true))
            }
        }
        else if (stateTicks > OPEN_TIMEOUT) {
            if (++ openAttempts >= MAX_OPEN_ATTEMPTS) return stop("§cCouldn't open the $name.")
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
                waitTicks = MacroConfig.clickDelay
            }
        }
        return false
    }

    private fun closeMenu(next: State) {
        mc.player?.closeContainer()
        setState(next, MacroConfig.clickDelay)
    }

    /** Turns towards [target] over a few ticks, then runs [action] on the tick after the rotation finished. */
    private fun lookThen(target: Vec3, action: () -> Unit) {
        val player = mc.player ?: return
        val rot = rotation ?: run {
            val eye = player.eyePosition
            val dx = target.x - eye.x
            val dy = target.y - eye.y
            val dz = target.z - eye.z
            val yaw = Math.toDegrees(- atan2(dx, dz)).toFloat()
            val pitch = Math.toDegrees(- atan2(dy, sqrt(dx * dx + dz * dz))).toFloat().coerceIn(- 90f, 90f)
            Rotation(player.yRot, player.xRot, player.yRot + Mth.wrapDegrees(yaw - player.yRot), pitch, MacroConfig.rotationTime / 50)
                .also { rotation = it }
        }

        rot.elapsed ++
        val t = if (rot.ticks <= 0) 1f else min(1f, rot.elapsed / rot.ticks.toFloat())
        val eased = t * t * (3 - 2 * t)
        player.yRot = rot.fromYaw + (rot.toYaw - rot.fromYaw) * eased
        player.xRot = rot.fromPitch + (rot.toPitch - rot.fromPitch) * eased
        player.yHeadRot = player.yRot
        if (rot.elapsed <= max(rot.ticks, 1)) return

        rotation = null
        action()
        interactSent = true
        stateTicks = 0
    }

    private fun interactEntity(entity: Entity) {
        val connection = mc.connection ?: return
        val sneaking = mc.player?.isShiftKeyDown ?: return
        val hitVec = Vec3(0.0, entity.bbHeight / 2.0, 0.0)
        connection.send(ServerboundInteractPacket.createInteractionPacket(entity, sneaking, InteractionHand.MAIN_HAND, hitVec))
        connection.send(ServerboundInteractPacket.createInteractionPacket(entity, sneaking, InteractionHand.MAIN_HAND))
    }

    private fun click(slot: Int, button: Int, type: ClickType) {
        val player = mc.player ?: return
        mc.gameMode?.handleInventoryMouseClick(player.containerMenu.containerId, slot, button, type, player)
        waitTicks = MacroConfig.clickDelay
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
        val radius = ceil(MacroConfig.reach).toInt()
        val origin = BlockPos.containing(eye)
        val tables = mutableListOf<BlockPos>()
        val chests = mutableListOf<BlockPos>()

        for (pos in BlockPos.betweenClosed(origin.offset(- radius, - radius, - radius), origin.offset(radius, radius, radius))) {
            if (! inReach(AABB(pos))) continue
            if (blockAt(pos) == Blocks.CRAFTING_TABLE) tables.add(pos.immutable())
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
        return blockAt(table) == Blocks.CRAFTING_TABLE && isChest(chestPos) && inReach(AABB(table)) && inReach(AABB(chestPos))
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

    private fun statusLine(): String {
        var line = "§bVillager Macro §7- §f${state.label} §7| §a${countItem(Items.EMERALD)} emeralds §7| §f${stringCount()} string" +
            " §7| Trades: §f$tradesDone §7| Blocks stored: §a$blocksStored"

        val now = System.currentTimeMillis()
        val nextCheck = when (state) {
            State.WAITING -> villagersInReach().filter { it.uuid !in ignoredVillagers }.minOfOrNull { villagerCooldowns[it.uuid] ?: now }
            State.STRING_COOLDOWN -> nextStringAttempt
            else -> null
        }
        nextCheck?.let { line += " §7| Next try in §f${max(0L, (it - now + 999) / 1000)}s" }
        return line
    }

    private fun isStringTrade(offer: MerchantOffer) = offer.costA.`is`(Items.STRING) && offer.costB.isEmpty && offer.result.`is`(Items.EMERALD)

    /** String in the payment slot and the inventory, i.e. everything this trade can use. */
    private fun stringIn(menu: MerchantMenu) = menu.slots.withIndex().sumOf { (index, slot) ->
        if (index != TRADE_RESULT_SLOT && slot.item.`is`(Items.STRING)) slot.item.count else 0
    }

    private fun emptySlots() = mc.player?.inventory?.nonEquipmentItems?.count { it.isEmpty } ?: 0

    private fun stringCount() =mc.player?.inventory?.nonEquipmentItems?.sumOf { if (it.`is`(Items.STRING)) it.count else 0 } ?: 0

    private fun blockAt(pos: BlockPos): Block? = mc.level?.getBlockState(pos)?.block

    private fun isChest(pos: BlockPos) = blockAt(pos).let { it == Blocks.CHEST || it == Blocks.TRAPPED_CHEST }

    /** Renamed or enchanted items won't stack with plain ones, so they are left alone. */
    private fun isPlain(stack: ItemStack, item: Item) = ! stack.isEmpty && ItemStack.isSameItemSameComponents(stack, item.defaultInstance)

    private fun countItem(item: Item) = mc.player?.inventory?.nonEquipmentItems?.sumOf { if (isPlain(it, item)) it.count else 0 } ?: 0

    private fun canFit(item: Item) = mc.player?.inventory?.nonEquipmentItems?.any {
        it.isEmpty || (isPlain(it, item) && it.count < it.maxStackSize)
    } == true

    private fun inReach(box: AABB, eye: Vec3? = mc.player?.eyePosition): Boolean {
        if (eye == null) return false
        return distanceSq(box, eye) <= MacroConfig.reach * MacroConfig.reach
    }

    private fun distanceSq(box: AABB, point: Vec3): Double {
        val dx = max(max(box.minX - point.x, point.x - box.maxX), 0.0)
        val dy = max(max(box.minY - point.y, point.y - box.maxY), 0.0)
        val dz = max(max(box.minZ - point.z, point.z - box.maxZ), 0.0)
        return dx * dx + dy * dy + dz * dz
    }
}
