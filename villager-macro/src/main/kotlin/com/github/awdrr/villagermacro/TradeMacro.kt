package com.github.awdrr.villagermacro

import com.github.awdrr.villagermacro.VillagerMacroMod.chat
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ServerboundInteractPacket
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
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.*
import kotlin.random.Random

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

    private const val CLICKS_PER_TICK_AT_MAX_SPEED = 4
    private const val OPEN_TIMEOUT = 40
    private const val MAX_OPEN_ATTEMPTS = 3
    private const val MAX_STALLS = 3
    private const val MAX_STATE_STEPS = 2000
    private const val VILLAGER_RETRY_MS = 30_000L

    private const val STRING_TIMEOUT_MS = 5_000L
    private const val STRING_RETRY_MS = 30_000L
    /** Used until there's enough history to time /string properly. */
    private const val PREFETCH_BELOW = 12 * 64
    private const val MIN_PREFETCH = 6 * 64
    private const val USAGE_WINDOW_MS = 3_000L
    private const val MIN_USAGE_SPAN_MS = 500L

    // Vanilla servers kick for spam above 200 points: +20 per chat message or command, -1 every tick.
    // Stay well under it (bursts are fine, ~1 command a second is sustainable) and leave room for your own chat.
    private const val SPAM_PER_COMMAND = 20
    private const val SPAM_LIMIT = 140
    private const val MAX_STRING_FAILS = 10

    private const val SERVER_TIMEOUT = 40
    private const val MIN_RECIPE_INTERVAL_MS = 250L

    private const val STORE_CLICK_DELAY_MS = 500L

    private const val PAYMENT_SLOT = 0
    /** The trade's second payment slot. The string trade doesn't use it, so it briefly holds an emerald while making room. */
    private const val PARK_SLOT = 1
    private val TRADE_INV_SLOTS = 3 .. 38
    private const val TRADE_RESULT_SLOT = 2
    private const val CRAFT_RESULT_SLOT = 0
    private val CRAFT_GRID_SLOTS = 1 .. 9
    private val CRAFT_INV_SLOTS = 10 .. 45


    var running = false
        private set
    private var state = State.FIND_VILLAGER
    private var waitTicks = 0
    private var stateTicks = 0
    private var stateSince = 0L
    private var interactSent = false
    private var aimMisses = 0
    private var nextStoreClickAt = 0L
    private var openAttempts = 0
    private var rotation: HumanTurn? = null
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

    private var stringPendingSince = 0L
    private var lastStringTotal = 0
    private var stringLatencyMs = 400.0
    private var stringUsed = 0L
    /** (trading clock, string used so far) samples. */
    private val usage = ArrayDeque<Pair<Long, Long>>()
    /** Only runs while actually trading, so turning and walking between villagers don't make string look slow to use. */
    private var tradingClockMs = 0L
    private var lastClockAt = 0L
    private var spamScore = 0
    private var spamLimited = false
    private var stringFails = 0
    private var nextStringAttempt = 0L

    private var gridTarget = 0
    private var pendingGridSlot = - 1
    private var pickupSource = CRAFT_INV_SLOTS.first
    private var lastCraftTotal = - 1
    private var craftStalls = 0
    private var useRecipeBook = true
    private var recipePending = false
    private var serverSyncId = - 1
    private var serverSyncAt = 0
    private var settledAfter = - 1
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
        stringPendingSince = 0L
        tradesDone = 0
        blocksStored = 0
        level = mc.level
        lastClockAt = 0L
        stateSince = System.currentTimeMillis()
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
            closeContainer()
        }

        mc.gui.setOverlayMessage(Component.empty(), false)
        message?.let { chat(it) }
    }

    fun onTick() {
        if (spamScore > 0) spamScore --
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

        // Notice delivered string every tick, whatever the macro is doing, so /string timing is measured right.
        val now = System.currentTimeMillis()
        val onHand = stringOnHand()
        if (state == State.TRADING && onHand >= (cheapestTrade ?: 1) && lastClockAt != 0L) tradingClockMs += now - lastClockAt
        lastClockAt = now
        trackString(onHand)

        if (waitTicks > 0) {
            waitTicks --
            return
        }

        stateTicks ++
        if (stateTicks > MAX_STATE_STEPS && state in MENU_STATES) return stop("§cStopped, got stuck while ${state.label.lowercase()}.")

        // Click delay 0: click several times per tick inside menus, like spam clicking at a high frame rate.
        // The server applies the clicks in order and the client predicts each one, so none get lost.
        val actions = if (MacroConfig.clickDelay == 0) CLICKS_PER_TICK_AT_MAX_SPEED else 1
        for (i in 0 until actions) {
            if (i > 0 && (! running || waitTicks > 0 || state !in MENU_STATES)) break
            step()
        }
    }

    private fun step() {
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
        val now = System.currentTimeMillis()
        if (newState != state) {
            debug("${state.label} took ${"%.1f".format((now - stateSince) / 1000.0)}s -> ${newState.label}")
            stateSince = now
        }
        state = newState
        waitTicks = delay
        stateTicks = 0
        interactSent = false
        aimMisses = 0
        nextStoreClickAt = 0L
        openAttempts = 0
        rotation = null
        serverSyncId = - 1
        settledAfter = - 1
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
            if (shouldCraftBeforeString()) return startCrafting()
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
        trackString(stringOnHand())
        // String that was asked for earlier may already be here.
        if (stringCount() >= (cheapestTrade ?: 1)) return setState(State.FIND_VILLAGER)

        if (! interactSent) {
            if (! clearScreen()) return
            if (! requestString(now)) return
            interactSent = true
            return
        }

        if (stringPendingSince == 0L) return setState(State.FIND_VILLAGER, 1)
        if (stringOverdue(now)) {
            stringPendingSince = 0L
            if (stringFailed(now)) setState(State.STRING_COOLDOWN)
        }
    }

    /** Sends the string command unless it's already on its way or the spam limit is close. True if string is on its way. */
    private fun requestString(now: Long): Boolean {
        if (stringPendingSince != 0L) return true
        if (spamScore + SPAM_PER_COMMAND > SPAM_LIMIT) {
            if (! spamLimited) debug("waiting for the chat spam limit before /${MacroConfig.stringCommand}")
            spamLimited = true
            return false
        }
        mc.connection?.sendCommand(MacroConfig.stringCommand) ?: return false
        spamLimited = false
        spamScore += SPAM_PER_COMMAND
        stringPendingSince = now
        debug("sent /${MacroConfig.stringCommand}: ${stringOnHand()} string on hand, ${emptySlots()} empty slots, spam budget ${SPAM_LIMIT - spamScore}")
        return true
    }

    private fun debug(message: String) {
        if (MacroConfig.debug) chat("§8[debug] §7$message")
    }

    /** Feed it the current string total. Only the string command makes it go up, so that marks the request as delivered. */
    private fun trackString(total: Int) {
        val now = System.currentTimeMillis()
        // /string hands out full stacks; smaller rises are just string moving around (e.g. a menu's leftovers coming back).
        if (stringPendingSince != 0L && total >= lastStringTotal + 64) {
            // Learn how long the server takes to hand it out. A slow one counts right away, fast ones ease it down.
            val took = (now - stringPendingSince).toDouble()
            stringLatencyMs = max(took, stringLatencyMs * 0.75 + took * 0.25)
            stringPendingSince = 0L
            stringFails = 0
            debug("string arrived: +${total - lastStringTotal} after ${took.toLong()} ms")
        }
        else if (total < lastStringTotal) stringUsed += lastStringTotal - total
        lastStringTotal = total

        usage.addLast(tradingClockMs to stringUsed)
        while (usage.size > 2 && tradingClockMs - usage.first().first > USAGE_WINDOW_MS) usage.removeFirst()
    }

    /** String traded away per millisecond of actual trading over the last few seconds, or null without enough history yet. */
    private fun stringPerMs(): Double? {
        val first = usage.firstOrNull() ?: return null
        val last = usage.last()
        val span = last.first - first.first
        if (span < MIN_USAGE_SPAN_MS) return null
        return (last.second - first.second).toDouble() / span
    }

    /**
     * /string only fills empty slots, so asking too early hands out little and asking too late means waiting.
     * Ask just early enough (with margin) that it arrives before running out: by then more slots have emptied.
     */
    private fun timeToAskForString(total: Int): Boolean {
        if (emptySlots() <= MacroConfig.craftAtFreeSlots) return false
        val rate = stringPerMs() ?: return total < PREFETCH_BELOW
        val needed = rate * (stringLatencyMs * 1.5 + 250) + 2 * 64
        return total < max(needed, MIN_PREFETCH.toDouble())
    }

    private fun stringOverdue(now: Long) = stringPendingSince != 0L && now - stringPendingSince > STRING_TIMEOUT_MS

    /** Counts a string command that gave nothing. Returns false if the macro gave up and stopped. */
    private fun stringFailed(now: Long): Boolean {
        val command = MacroConfig.stringCommand
        if (++ stringFails >= MAX_STRING_FAILS) {
            stop("§c/$command didn't give you any string $MAX_STRING_FAILS times in a row.", closeMenu = true)
            return false
        }
        if (stringFails == 1) chat("§e/$command didn't give you any string, trying again every ${STRING_RETRY_MS / 1000}s.")
        nextStringAttempt = now + STRING_RETRY_MS
        return true
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
            val box = target.boundingBox
            val aim = Vec3(
                box.center.x + rand(- 0.15, 0.15) * box.xsize,
                box.minY + rand(0.55, 0.85) * box.ysize,
                box.center.z + rand(- 0.15, 0.15) * box.zsize
            )
            lookThen(aim) {
                interactEntity(target)
                true
            }
        }
        else if (stateTicks > OPEN_TIMEOUT) {
            // Sleeping, jobless or busy villagers never open the trade menu, try them again later.
            villagerCooldowns[target.uuid] = System.currentTimeMillis() + VILLAGER_RETRY_MS
            debug("villager didn't open its trades (asleep or busy?), trying it again in 30s")
            setState(State.FIND_VILLAGER)
        }
    }

    private fun trade() {
        val menu = mc.player !!.containerMenu as? MerchantMenu ?: return setState(State.FIND_VILLAGER, MacroConfig.clickDelay)
        val target = villager ?: return closeMenu(State.FIND_VILLAGER)
        val now = System.currentTimeMillis()

        trackString(stringOnHand())

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
            debug("villager has no string trade, skipping it")
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
                debug("trades stopped going through, trying this villager again in 30s")
                return closeMenu(State.FIND_VILLAGER)
            }
        }

        val carried = menu.carried
        // Finish making room for an emerald first (see makeRoomStep), it has one on the cursor or parked.
        if (carried.`is`(Items.EMERALD) || menu.getSlot(PARK_SLOT).item.`is`(Items.EMERALD)) {
            if (makeRoomStep(menu)) return
            needsCrafting = true
            return closeMenu(State.FIND_VILLAGER)
        }
        if (! carried.isEmpty) return placeCarriedString(menu, carried, menu.getSlot(PAYMENT_SLOT).item)

        if (offer.isOutOfStock) {
            villagerCooldowns[target.uuid] = now + MacroConfig.restockDelay * 1000L
            debug("villager sold out, checking it again in ${MacroConfig.restockDelay}s (${availableVillagers()} others ready)")
            return closeMenu(State.FIND_VILLAGER)
        }

        // Ask for more string before running out, so it has usually arrived by the time it's needed.
        // A request that never got answered while there's still string to trade with is simply retried.
        if (stringOverdue(now) && stringIn(menu) >= cost) stringPendingSince = 0L
        if (timeToAskForString(stringIn(menu))) requestString(now)

        if (paidString(menu) < cost) {
            // Same as pressing space on the selected trade: picks it again, which refills the payment slot with string.
            menu.setSelectionHint(index)
            menu.tryMoveItems(index)
            mc.connection?.send(ServerboundSelectTradePacket(index))

            if (paidString(menu) < cost) {
                val available = stringIn(menu)
                if (available < cost) {
                    // Can't afford this villager but a cheaper one exists: use that one.
                    if (available >= (cheapestTrade ?: cost)) {
                        villagerCooldowns[target.uuid] = now + VILLAGER_RETRY_MS
                        debug("villager wants $cost string, going to a cheaper one")
                        return closeMenu(State.FIND_VILLAGER)
                    }
                    // String already on its way: wait for it here, with the trade menu open.
                    if (stringPendingSince != 0L) {
                        if (stringOverdue(now)) {
                            stringPendingSince = 0L
                            if (stringFailed(now)) {
                                closeContainer()
                                setState(State.STRING_COOLDOWN)
                            }
                        }
                        return
                    }
                    // Time to craft first, or no room for string or emeralds: findNextAction() sorts that out.
                    if (shouldCraftBeforeString() || ! canFit(Items.STRING) || ! canFit(Items.EMERALD)) {
                        debug("out of string with ${emptySlots()} empty slots: crafting first")
                        return closeMenu(State.FIND_VILLAGER)
                    }
                    // Otherwise get more string without leaving the trade menu (waits here if the spam limit says so).
                    requestString(now)
                    return
                }

                // The game couldn't refill it (no room to take the leftover string back first),
                // so top it up by hand: pick up a stack of string here, drop it on the payment slot next step.
                val payment = menu.getSlot(PAYMENT_SLOT).item
                val source = TRADE_INV_SLOTS.filter {
                    val stack = menu.getSlot(it).item
                    stack.`is`(Items.STRING) && (payment.isEmpty || ItemStack.isSameItemSameComponents(stack, payment))
                }.maxByOrNull { menu.getSlot(it).item.count } ?: return closeMenu(State.FIND_VILLAGER)

                pickupSource = source
                click(source, 0, ClickType.PICKUP)
                trackString(stringOnHand())
                return
            }
        }

        // No room for the next emerald. If string is what's filling the inventory (/string fills every empty slot),
        // make room instead of crafting: trade once onto the cursor, then makeRoomStep() takes it from there.
        if (! canFit(Items.EMERALD)) {
            val roomAfterOneTrade = 64 - (paidString(menu) - cost)
            if (menu.carried.isEmpty && ! menu.getSlot(PARK_SLOT).hasItem() && smallStringStack(menu, roomAfterOneTrade) != null) {
                usesAtClick = offer.uses
                return click(TRADE_RESULT_SLOT, 0, ClickType.PICKUP)
            }
            needsCrafting = true
            return closeMenu(State.FIND_VILLAGER)
        }

        // Shift click the output in the same tick as the refill. It keeps trading until the payment slot runs low.
        usesAtClick = offer.uses
        click(TRADE_RESULT_SLOT, 0, ClickType.QUICK_MOVE)
        trackString(stringOnHand())
    }

    private fun paidString(menu: MerchantMenu) = menu.getSlot(PAYMENT_SLOT).item.let { if (it.`is`(Items.STRING)) it.count else 0 }

    /**
     * Makes room for an emerald that was traded onto the cursor, one click per call:
     * park it in the second payment slot, merge a small string stack into the payment slot (which empties its
     * inventory slot), then pick the emerald back up and put it in that slot. Returns false if it can't go on.
     */
    private fun makeRoomStep(menu: MerchantMenu): Boolean {
        val carried = menu.carried
        val parked = menu.getSlot(PARK_SLOT).item
        val emptySlot = pickupSource.takeIf { it in TRADE_INV_SLOTS && ! menu.getSlot(it).hasItem() }
            ?: TRADE_INV_SLOTS.firstOrNull { ! menu.getSlot(it).hasItem() }

        when {
            carried.`is`(Items.EMERALD) -> when {
                emptySlot != null -> click(emptySlot, 0, ClickType.PICKUP)
                parked.isEmpty -> click(PARK_SLOT, 0, ClickType.PICKUP)
                else -> return false
            }

            carried.`is`(Items.STRING) -> placeCarriedString(menu, carried, menu.getSlot(PAYMENT_SLOT).item)

            parked.`is`(Items.EMERALD) -> {
                if (emptySlot != null) click(PARK_SLOT, 0, ClickType.PICKUP)
                else {
                    val stack = smallStringStack(menu, 64 - paidString(menu)) ?: return false
                    pickupSource = stack
                    click(stack, 0, ClickType.PICKUP)
                }
            }

            else -> return false
        }
        trackString(stringOnHand())
        return true
    }

    /** Smallest string stack that fits into the payment slot whole (at most [room] string). */
    private fun smallStringStack(menu: MerchantMenu, room: Int): Int? {
        val payment = menu.getSlot(PAYMENT_SLOT).item
        return TRADE_INV_SLOTS.filter {
            val stack = menu.getSlot(it).item
            stack.`is`(Items.STRING) && stack.count <= room && (payment.isEmpty || ItemStack.isSameItemSameComponents(stack, payment))
        }.minByOrNull { menu.getSlot(it).item.count }
    }

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
        if (waitingForServer(menu)) return

        val carried = menu.carried
        if (! carried.isEmpty) return placeCarried(menu, carried)

        if (useRecipeBook) craftWithRecipeBook(menu)
        else craftByHand(menu)
    }

    /**
     * The client can't predict crafting: after a recipe book fill or a shift-clicked output it only guesses
     * (one craft), and the real grid comes from the server a moment later. Acting on the guess mixes up the grid,
     * so after those two actions wait for the server's answer, plus a tick for the whole update to land.
     */
    private fun waitingForServer(menu: CraftingMenu): Boolean {
        if (serverSyncId >= 0) {
            if (menu.stateId == serverSyncId && stateTicks - serverSyncAt <= SERVER_TIMEOUT) return true
            serverSyncId = - 1
            settledAfter = stateTicks
        }
        return stateTicks <= settledAfter
    }

    /** Call right before the action, so any change after it counts as the server's answer. */
    private fun awaitServer(menu: CraftingMenu) {
        serverSyncId = menu.stateId
        serverSyncAt = stateTicks
    }

    /**
     * Same as a player using the recipe book: shift click the emerald block recipe (fills the grid with as many
     * emeralds as fit, up to a stack per slot), then shift click the output. Two actions per 64 blocks.
     */
    private fun craftWithRecipeBook(menu: CraftingMenu) {
        val inGrid = CRAFT_GRID_SLOTS.sumOf { menu.getSlot(it).item.count }
        val hasResult = isPlain(menu.getSlot(CRAFT_RESULT_SLOT).item, Items.EMERALD_BLOCK)

        if (recipePending) {
            recipePending = false
            // The server answered (or never did) without filling the grid, so the recipe book won't work here.
            if (! hasResult) {
                useRecipeBook = false
                return
            }
        }

        if (inGrid > 0 && hasResult) return craftResult(menu, countItem(Items.EMERALD) + inGrid)

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
        awaitServer(menu)
        mc.gameMode?.handlePlaceRecipe(menu.containerId, recipe, true)
        lastRecipePlace = now
        recipePending = true
    }

    /** The emerald block recipe from the recipe book, if it's unlocked (it is once you've had an emerald). */
    private fun emeraldBlockRecipe() = mc.level?.let { level ->
        val context = SlotDisplayContext.fromLevel(level)
        mc.player?.recipeBook?.collections?.asSequence()?.flatMap { it.recipes }
            ?.firstOrNull { entry -> entry.resultItems(context).any { isPlain(it, Items.EMERALD_BLOCK) } }
            ?.id()
    }

    private fun craftResult(menu: CraftingMenu, total: Int) {
        if (total != lastCraftTotal) craftStalls = 0
        else if (++ craftStalls >= MAX_STALLS) return stop("§cCouldn't craft emerald blocks, is your inventory full?", closeMenu = true)
        lastCraftTotal = total
        awaitServer(menu)
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
        if (gridSlot == null) return craftResult(menu, total)

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

        // Take it slow in the chest: 500 ms before each stack goes in.
        val now = System.currentTimeMillis()
        if (nextStoreClickAt == 0L) nextStoreClickAt = now + STORE_CLICK_DELAY_MS
        if (now < nextStoreClickAt) return

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
        nextStoreClickAt = now + STORE_CLICK_DELAY_MS
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
            if (aimMisses >= MAX_OPEN_ATTEMPTS) return stop("§cCouldn't get the crosshair on the $name, is something in the way?")
            val aim = aimPoint(pos) ?: return stop("§cCan't see the $name from here (too far, or something's in the way).")
            lookThen(aim) { rightClickBlock(pos) }
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
                closeContainer()
                waitTicks = MacroConfig.clickDelay
            }
        }
        return false
    }

    private fun closeMenu(next: State) {
        closeContainer()
        setState(next, MacroConfig.clickDelay)
    }

    /**
     * Closes whatever menu is open. A trade menu gets its payment slots shift-clicked back into the inventory first:
     * otherwise the client drops that string from view until the server returns it, and its comeback looks like
     * /string delivering, which made the macro send extra /string commands.
     */
    private fun closeContainer() {
        val player = mc.player ?: return
        val menu = player.containerMenu
        if (menu is MerchantMenu && menu.carried.isEmpty) {
            for (slot in listOf(PAYMENT_SLOT, PARK_SLOT)) {
                if (menu.getSlot(slot).hasItem()) mc.gameMode?.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player)
            }
        }
        if (mc.screen is AbstractContainerScreen<*> || player.containerMenu !== player.inventoryMenu) player.closeContainer()
        lastStringTotal = stringOnHand()
    }

    /** Turns towards [target] over a few ticks, then runs [action] on the tick after the rotation finished. */
    /** Turns towards [target], then runs [action] once the turn is done. If the action says it couldn't act, it aims again. */
    private fun lookThen(target: Vec3, action: () -> Boolean) {
        val player = mc.player ?: return
        val rot = rotation ?: run {
            val eye = player.eyePosition
            val dx = target.x - eye.x
            val dy = target.y - eye.y
            val dz = target.z - eye.z
            val yaw = Math.toDegrees(- atan2(dx, dz)).toFloat()
            val pitch = Math.toDegrees(- atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
            HumanTurn(player.yRot, player.xRot, yaw, pitch, MacroConfig.rotationTime).also { rotation = it }
        }

        // Act on the tick after the turn finished, so the server has seen where we're looking.
        if (! rot.finished) {
            rot.finished = applyRotation()
            return
        }

        rotation = null
        if (action()) {
            interactSent = true
            stateTicks = 0
        }
        else aimMisses ++
    }

    /** Called every frame (and tick) while turning, so slow turns look smooth. Returns true once the turn is done. */
    fun applyRotation(): Boolean {
        val turn = rotation ?: return true
        val player = mc.player ?: return true
        val t = turn.progress()
        val (yaw, pitch) = turn.angleAt(t)
        // Move in whole mouse steps for your sensitivity, like real mouse input does.
        val step = mouseStep()
        player.yRot += snapToStep(yaw - player.yRot, step)
        player.xRot = (player.xRot + snapToStep(pitch - player.xRot, step)).coerceIn(- 90f, 90f)
        player.yHeadRot = player.yRot
        return t >= 1f
    }

    /** Smallest turn one pixel of mouse movement makes at the current sensitivity (vanilla's formula). */
    private fun mouseStep(): Float {
        val f = mc.options.sensitivity().get() * 0.6 + 0.2
        return (f * f * f * 1.2).toFloat()
    }

    private fun snapToStep(delta: Float, step: Float) = if (step <= 0f) delta else (delta / step).roundToInt() * step

    fun onFrame() {
        if (running) applyRotation()
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

    /**
     * A point on [pos] that's in reach and that the crosshair would really land on from here, checked with the
     * game's own line of sight test (so e.g. the chest above the crafting table can't be in the way).
     * Faces pointing at the player come first, the middle of a face before its edges.
     */
    private fun aimPoint(pos: BlockPos): Vec3? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val eye = player.eyePosition
        val shape = level.getBlockState(pos).getShape(level, pos)
        val box = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        val center = box.center
        val reach = player.blockInteractionRange()

        val faces = Direction.entries.sortedByDescending {
            (eye.x - center.x) * it.stepX + (eye.y - center.y) * it.stepY + (eye.z - center.z) * it.stepZ
        }
        // A few random spots first so it doesn't always click the exact same point, then fixed ones as a fallback.
        val offsets = List(3) { rand(- 0.35, 0.35) to rand(- 0.35, 0.35) } +
            listOf(0.0 to 0.0, 0.3 to 0.3, 0.3 to - 0.3, - 0.3 to 0.3, - 0.3 to - 0.3)

        for (face in faces) for ((a, b) in offsets) {
            // Just inside the face, so the line of sight ends in the block itself.
            val half = Vec3(box.xsize / 2, box.ysize / 2, box.zsize / 2)
            val point = when (face.axis) {
                Direction.Axis.X -> Vec3(center.x + face.stepX * (half.x - 0.01), center.y + a * half.y, center.z + b * half.z)
                Direction.Axis.Y -> Vec3(center.x + a * half.x, center.y + face.stepY * (half.y - 0.01), center.z + b * half.z)
                Direction.Axis.Z -> Vec3(center.x + a * half.x, center.y + b * half.y, center.z + face.stepZ * (half.z - 0.01))
            }
            if (eye.distanceTo(point) > reach) continue
            val hit = level.clip(ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.BLOCK && hit.blockPos == pos) return point
        }
        return null
    }

    /**
     * Right clicks like the player would: presses the use key, and the game uses whatever the crosshair is on.
     * Only does it when the crosshair really is on the block.
     */
    private fun rightClickBlock(pos: BlockPos): Boolean {
        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK || hit.blockPos != pos) return false
        // The game ignores key presses while a screen is open (the pause screen opens by itself when unfocused).
        if (mc.screen is PauseScreen) mc.setScreen(null)
        KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyUse))
        return true
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

    private fun availableVillagers(): Int {
        val now = System.currentTimeMillis()
        return villagersInReach().count { it.uuid !in ignoredVillagers && (villagerCooldowns[it.uuid] ?: 0L) <= now && it != villager }
    }

    private fun nextVillager(): Entity? {
        val now = System.currentTimeMillis()
        return villagersInReach().firstOrNull { it.uuid !in ignoredVillagers && (villagerCooldowns[it.uuid] ?: 0L) <= now }
    }

    private fun statusLine(): String {
        val label = when {
            spamLimited -> "${state.label} (waiting: chat spam limit)"
            state == State.TRADING && stringPendingSince != 0L -> "Trading (getting string)"
            else -> state.label
        }
        var line = "§bVillager Macro §7- §f$label §7| §a${countItem(Items.EMERALD)} emeralds §7| §f${stringCount()} string" +
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

    private fun shouldCraftBeforeString() = emptySlots() <= MacroConfig.craftAtFreeSlots && countItem(Items.EMERALD) >= 9

    private fun rand(from: Double, to: Double) = from + Random.nextDouble() * (to - from)

    private fun emptySlots() = mc.player?.inventory?.nonEquipmentItems?.count { it.isEmpty } ?: 0

    /** All string the player has: inventory, plus a trade menu's payment slots and the cursor. Only /string makes it go up. */
    private fun stringOnHand(): Int {
        val menu = mc.player?.containerMenu ?: return stringCount()
        var count = stringCount()
        if (menu is MerchantMenu) count += (0 .. 1).sumOf { menu.getSlot(it).item.let { stack -> if (stack.`is`(Items.STRING)) stack.count else 0 } }
        if (menu.carried.`is`(Items.STRING)) count += menu.carried.count
        return count
    }

    private fun stringCount() = mc.player?.inventory?.nonEquipmentItems?.sumOf { if (it.`is`(Items.STRING)) it.count else 0 } ?: 0

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
