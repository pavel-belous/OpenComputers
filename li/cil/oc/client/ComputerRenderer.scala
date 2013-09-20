package li.cil.oc.client

import org.lwjgl.opengl.GL11
import li.cil.oc.common.tileentity.TileEntityComputer
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer
import net.minecraft.tileentity.TileEntity
import net.minecraftforge.common.ForgeDirection
import net.minecraft.client.renderer.Tessellator
import net.minecraft.util.ResourceLocation

object ComputerRenderer extends TileEntitySpecialRenderer {
  private val frontOn = new ResourceLocation("opencomputers", "textures/blocks/computer_front_on.png")

  def renderTileEntityAt(tileEntity: TileEntity, x: Double, y: Double, z: Double, f: Float) = {
    val computer = tileEntity.asInstanceOf[TileEntityComputer]
    if (computer.isOn) {
      GL11.glPushAttrib(0xFFFFFF)
      GL11.glPushMatrix()
      GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5)

      computer.yaw match {
        case ForgeDirection.WEST => GL11.glRotatef(-90, 0, 1, 0)
        case ForgeDirection.NORTH => GL11.glRotatef(180, 0, 1, 0)
        case ForgeDirection.EAST => GL11.glRotatef(90, 0, 1, 0)
        case _ => // No yaw.
      }

      GL11.glEnable(GL11.GL_BLEND)
      GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR)
      GL11.glDepthFunc(GL11.GL_LEQUAL)

      OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 200, 200)

      GL11.glTranslatef(-0.5f, 0.5f, 0.501f)
      GL11.glScalef(1, -1, 1)

      setTexture(frontOn)
      val t = Tessellator.instance
      t.startDrawingQuads()
      t.addVertexWithUV(0, 1, 0, 0, 1)
      t.addVertexWithUV(1, 1, 0, 1, 1)
      t.addVertexWithUV(1, 0, 0, 1, 0)
      t.addVertexWithUV(0, 0, 0, 0, 0)
      t.draw()

      GL11.glPopMatrix()
      GL11.glPopAttrib()
    }
  }

  private def setTexture(resource: ResourceLocation) = func_110628_a(resource)
}