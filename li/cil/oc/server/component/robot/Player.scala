package li.cil.oc.server.component.robot

import li.cil.oc.Settings
import li.cil.oc.common.tileentity.Robot
import li.cil.oc.util.mods.PortalGun
import net.minecraft.block.{BlockPistonBase, BlockFluid, Block}
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.player.{EnumStatus, EntityPlayer}
import net.minecraft.entity.{EntityLivingBase, Entity}
import net.minecraft.item.{ItemBlock, ItemStack}
import net.minecraft.potion.PotionEffect
import net.minecraft.server.MinecraftServer
import net.minecraft.util._
import net.minecraft.world.World
import net.minecraftforge.common.{ForgeHooks, ForgeDirection}
import net.minecraftforge.event.entity.player.PlayerInteractEvent.Action
import net.minecraftforge.event.{Event, ForgeEventFactory}
import net.minecraftforge.fluids.FluidRegistry
import scala.Some
import scala.collection.convert.WrapAsScala._
import scala.reflect._

class Player(val robot: Robot) extends EntityPlayer(robot.world, Settings.get.nameFormat.replace("$player$", robot.owner).replace("$random$", (robot.world.rand.nextInt(0xFFFFFF) + 1).toString)) {
  capabilities.allowFlying = true
  capabilities.disableDamage = true
  capabilities.isFlying = true
  yOffset = 0.5f
  eyeHeight = 0f
  setSize(1, 1)

  val robotInventory = new Inventory(this)
  inventory = robotInventory

  def world = robot.worldObj

  override def getPlayerCoordinates = new ChunkCoordinates(robot.x, robot.y, robot.z)

  // ----------------------------------------------------------------------- //

  def updatePositionAndRotation(facing: ForgeDirection, side: ForgeDirection) {
    // Slightly offset in robot's facing to avoid glitches (e.g. Portal Gun).
    val direction = Vec3.createVectorHelper(
      facing.offsetX + side.offsetX * 0.5 + robot.facing.offsetX * 0.01,
      facing.offsetY + side.offsetY * 0.5 + robot.facing.offsetY * 0.01,
      facing.offsetZ + side.offsetZ * 0.5 + robot.facing.offsetZ * 0.01).normalize()
    val yaw = Math.toDegrees(-Math.atan2(direction.xCoord, direction.zCoord)).toFloat
    val pitch = Math.toDegrees(-Math.atan2(direction.yCoord, Math.sqrt((direction.xCoord * direction.xCoord) + (direction.zCoord * direction.zCoord)))).toFloat * 0.99f
    setLocationAndAngles(robot.x + 0.5, robot.y, robot.z + 0.5, yaw, pitch)
    prevRotationPitch = rotationPitch
    prevRotationYaw = rotationYaw
  }

  def closestLivingEntity(side: ForgeDirection) = {
    entitiesOnSide[EntityLivingBase](side).
      foldLeft((Double.PositiveInfinity, None: Option[EntityLivingBase])) {
      case ((bestDistance, bestEntity), entity: EntityLivingBase) =>
        val distance = entity.getDistanceSqToEntity(this)
        if (distance < bestDistance) (distance, Some(entity))
        else (bestDistance, bestEntity)
      case (best, _) => best
    } match {
      case (_, Some(entity)) => Some(entity)
      case _ => None
    }
  }

  def entitiesOnSide[Type <: Entity : ClassTag](side: ForgeDirection) = {
    val (bx, by, bz) = (robot.x + side.offsetX, robot.y + side.offsetY, robot.z + side.offsetZ)
    entitiesInBlock[Type](bx, by, bz)
  }

  def entitiesInBlock[Type <: Entity : ClassTag](x: Int, y: Int, z: Int) = {
    val bounds = AxisAlignedBB.getAABBPool.getAABB(x, y, z, x + 1, y + 1, z + 1)
    world.getEntitiesWithinAABB(classTag[Type].runtimeClass, bounds).map(_.asInstanceOf[Type])
  }

  // ----------------------------------------------------------------------- //

  override def attackTargetEntityWithCurrentItem(entity: Entity) {
    entity match {
      case player: EntityPlayer if !canAttackPlayer(player) => // Avoid player damage.
      case _ =>
        val stack = getCurrentEquippedItem
        val oldDamage = if (stack != null) getCurrentEquippedItem.getItemDamage else 0
        super.attackTargetEntityWithCurrentItem(entity)
        if (stack != null && stack.stackSize > 0) {
          tryRepair(stack, oldDamage)
        }
    }
  }

  def activateBlockOrUseItem(x: Int, y: Int, z: Int, side: Int, hitX: Float, hitY: Float, hitZ: Float, duration: Double): ActivationType.Value = {
    val event = ForgeEventFactory.onPlayerInteract(this, Action.RIGHT_CLICK_BLOCK, x, y, z, side)
    if (event.isCanceled || event.useBlock == Event.Result.DENY) {
      return ActivationType.None
    }

    val stack = inventory.getCurrentItem
    val item = if (stack != null) stack.getItem else null
    if (!PortalGun.isPortalGun(stack)) {
      if (item != null && item.onItemUseFirst(stack, this, world, x, y, z, side, hitX, hitY, hitZ)) {
        if (stack.stackSize <= 0) ForgeEventFactory.onPlayerDestroyItem(this, stack)
        if (stack.stackSize <= 0) inventory.setInventorySlotContents(0, null)
        return ActivationType.ItemUsed
      }
    }

    val blockId = world.getBlockId(x, y, z)
    val block = Block.blocksList(blockId)
    val canActivate = block != null && Settings.get.allowActivateBlocks
    val shouldActivate = canActivate && (!isSneaking || (item == null || item.shouldPassSneakingClickToBlock(world, x, y, z)))
    if (shouldActivate && block.onBlockActivated(world, x, y, z, this, side, hitX, hitY, hitZ)) {
      return ActivationType.BlockActivated
    }

    if (stack != null) {
      val didPlace = tryPlaceBlockWhileHandlingFunnySpecialCases(stack, x, y, z, side, hitX, hitY, hitZ)
      if (stack.stackSize <= 0) ForgeEventFactory.onPlayerDestroyItem(this, stack)
      if (stack.stackSize <= 0) inventory.setInventorySlotContents(0, null)
      if (didPlace) {
        return ActivationType.ItemPlaced
      }

      if (tryUseItem(stack, duration)) {
        return ActivationType.ItemUsed
      }
    }

    ActivationType.None
  }

  def useEquippedItem(duration: Double) = {
    val event = ForgeEventFactory.onPlayerInteract(this, Action.RIGHT_CLICK_AIR, 0, 0, 0, -1)
    if (!event.isCanceled && event.useItem != Event.Result.DENY) {
      tryUseItem(getCurrentEquippedItem, duration)
    }
    else false
  }

  private def tryUseItem(stack: ItemStack, duration: Double) = {
    clearItemInUse()
    stack != null && stack.stackSize > 0 &&
      (Settings.get.allowUseItemsWithDuration || stack.getMaxItemUseDuration <= 0) &&
      (!PortalGun.isPortalGun(stack) || PortalGun.isStandardPortalGun(stack)) && {
      val oldSize = stack.stackSize
      val oldDamage = if (stack != null) stack.getItemDamage else 0
      val heldTicks = 0 max stack.getMaxItemUseDuration min (duration * 20).toInt
      val newStack = stack.useItemRightClick(world, this)
      if (isUsingItem) {
        getItemInUse.onPlayerStoppedUsing(world, this, getItemInUse.getMaxItemUseDuration - heldTicks)
        clearItemInUse()
      }
      robot.computer.pause(heldTicks / 20.0)
      val stackChanged = newStack != stack || (newStack != null && (newStack.stackSize != oldSize || newStack.getItemDamage != oldDamage))
      if (newStack == stack && stack.stackSize > 0) {
        tryRepair(stack, oldDamage)
      }
      stackChanged && {
        if (newStack.stackSize <= 0) ForgeEventFactory.onPlayerDestroyItem(this, newStack)
        if (newStack.stackSize > 0) inventory.setInventorySlotContents(0, newStack)
        else inventory.setInventorySlotContents(0, null)
        true
      }
    }
  }

  def placeBlock(stack: ItemStack, x: Int, y: Int, z: Int, side: Int, hitX: Float, hitY: Float, hitZ: Float): Boolean = {
    val event = ForgeEventFactory.onPlayerInteract(this, Action.RIGHT_CLICK_BLOCK, x, y, z, side)
    if (event.isCanceled) {
      return false
    }

    event.useBlock == Event.Result.DENY || {
      val result = tryPlaceBlockWhileHandlingFunnySpecialCases(stack, x, y, z, side, hitX, hitY, hitZ)
      if (stack.stackSize <= 0) ForgeEventFactory.onPlayerDestroyItem(this, stack)
      result
    }
  }

  def clickBlock(x: Int, y: Int, z: Int, side: Int): Boolean = {
    val event = ForgeEventFactory.onPlayerInteract(this, Action.LEFT_CLICK_BLOCK, x, y, z, side)
    if (event.isCanceled) {
      return false
    }

    // TODO Is this already handled via the event?
    if (MinecraftServer.getServer.isBlockProtected(world, x, y, z, this)) {
      return false
    }

    val blockId = world.getBlockId(x, y, z)
    val block = Block.blocksList(blockId)
    val metadata = world.getBlockMetadata(x, y, z)
    val mayClickBlock = event.useBlock != Event.Result.DENY && blockId > 0 && block != null
    val canClickBlock = mayClickBlock &&
      !block.isAirBlock(world, x, y, z) &&
      FluidRegistry.lookupFluidForBlock(block) == null &&
      !block.isInstanceOf[BlockFluid]
    if (!canClickBlock) {
      return false
    }

    block.onBlockClicked(world, x, y, z, this)
    world.extinguishFire(this, x, y, z, side)

    val isBlockUnbreakable = block.getBlockHardness(world, x, y, z) < 0
    val canDestroyBlock = !isBlockUnbreakable && block.canEntityDestroy(world, x, y, z, this)
    if (!canDestroyBlock) {
      return false
    }

    if (world.getWorldInfo.getGameType.isAdventure && !isCurrentToolAdventureModeExempt(x, y, z)) {
      return false
    }

    if (!ForgeHooks.canHarvestBlock(block, this, metadata)) {
      return false
    }

    val stack = getCurrentEquippedItem
    if (stack != null && stack.getItem.onBlockStartBreak(stack, x, y, z, this)) {
      return false
    }

    world.playAuxSFXAtEntity(this, 2001, x, y, z, blockId + (metadata << 12))

    if (stack != null) {
      val oldDamage = stack.getItemDamage
      stack.onBlockDestroyed(world, blockId, x, y, z, this)
      if (stack.stackSize == 0) {
        destroyCurrentEquippedItem()
      }
      else {
        tryRepair(stack, oldDamage)
      }
    }

    val itemsBefore = entitiesInBlock[EntityItem](x, y, z)
    block.onBlockHarvested(world, x, y, z, metadata, this)
    if (block.removeBlockByPlayer(world, this, x, y, z)) {
      block.onBlockDestroyedByPlayer(world, x, y, z, metadata)
      if (block.canHarvestBlock(this, metadata)) {
        block.harvestBlock(world, this, x, y, z, metadata)
        val itemsAfter = entitiesInBlock[EntityItem](x, y, z)
        val itemsDropped = itemsAfter -- itemsBefore
        for (drop <- itemsDropped) {
          drop.delayBeforeCanPickup = 0
          drop.onCollideWithPlayer(this)
        }
      }
      return true
    }
    false
  }

  override def dropPlayerItemWithRandomChoice(stack: ItemStack, inPlace: Boolean) =
    robot.spawnStackInWorld(stack, if (inPlace) ForgeDirection.UNKNOWN else robot.facing)

  private def tryRepair(stack: ItemStack, oldDamage: Int) {
    val needsRepairing = stack.isItemStackDamageable && stack.getItemDamage > oldDamage
    val shouldRepair = needsRepairing && getRNG.nextDouble() >= Settings.get.itemDamageRate
    if (shouldRepair) {
      // If an item takes a lot of damage at once we don't necessarily want to
      // make *all* of that damage go away. Instead we scale it according to
      // our damage probability. This makes sure we don't discard massive
      // damage spikes (e.g. on axes when using the treecapitator mod or such).
      val addedDamage = ((stack.getItemDamage - oldDamage) * Settings.get.itemDamageRate).toInt
      stack.setItemDamage(oldDamage + addedDamage)
    }
  }

  private def tryPlaceBlockWhileHandlingFunnySpecialCases(stack: ItemStack, x: Int, y: Int, z: Int, side: Int, hitX: Float, hitY: Float, hitZ: Float) = {
    val fakeEyeHeight = if (rotationPitch < 0 && isSomeKindOfPiston(stack)) 1.82 else 0
    setPosition(posX, posY - fakeEyeHeight, posZ)
    val didPlace = stack.tryPlaceItemIntoWorld(this, world, x, y, z, side, hitX, hitY, hitZ)
    setPosition(posX, posY + fakeEyeHeight, posZ)
    didPlace
  }

  private def isSomeKindOfPiston(stack: ItemStack) =
    stack.getItem match {
      case itemBlock: ItemBlock if itemBlock.getBlockID > 0 =>
        val block = Block.blocksList(itemBlock.getBlockID)
        block != null && block.isInstanceOf[BlockPistonBase]
      case _ => false
    }

  // ----------------------------------------------------------------------- //

  override def addExhaustion(amount: Float) {
    if (Settings.get.robotExhaustionCost > 0) {
      robot.battery.changeBuffer(-Settings.get.robotExhaustionCost * amount)
    }
  }

  override def openGui(mod: AnyRef, modGuiId: Int, world: World, x: Int, y: Int, z: Int) {}

  override def closeScreen() {}

  override def swingItem() {}

  override def canAttackPlayer(player: EntityPlayer) =
    Settings.get.canAttackPlayers && super.canAttackPlayer(player)

  override def canEat(value: Boolean) = false

  override def isPotionApplicable(effect: PotionEffect) = false

  override def attackEntityAsMob(entity: Entity) = false

  override def attackEntityFrom(source: DamageSource, damage: Float) = false

  override def isEntityInvulnerable = true

  override def heal(amount: Float) {}

  override def setHealth(value: Float) {}

  override def setDead() = isDead = true

  override def onDeath(source: DamageSource) {}

  override def onUpdate() {}

  override def onLivingUpdate() {}

  override def setCurrentItemOrArmor(slot: Int, stack: ItemStack) {}

  override def setRevengeTarget(entity: EntityLivingBase) {}

  override def setLastAttacker(entity: Entity) {}

  override def mountEntity(entity: Entity) {}

  override def travelToDimension(dimension: Int) {}

  override def sleepInBedAt(x: Int, y: Int, z: Int) = EnumStatus.OTHER_PROBLEM

  override def interactWith(entity: Entity) = false // TODO Or do we want this?

  def canCommandSenderUseCommand(i: Int, s: String) = false

  def sendChatToPlayer(message: ChatMessageComponent) {}
}