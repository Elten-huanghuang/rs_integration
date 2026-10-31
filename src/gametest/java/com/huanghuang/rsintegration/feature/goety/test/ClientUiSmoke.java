package com.huanghuang.rsintegration.feature.goety.test;

import com.huanghuang.rsintegration.feature.goety.client.WitchCauldronScreen;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.feature.goety.brewing.CauldronInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** End-to-end notebook navigation and container exchange in an isolated client world. */
@Mod.EventBusSubscriber(modid="rs_integration",value=Dist.CLIENT)
public final class ClientUiSmoke {
    private static boolean opened;
    private static int ticks, phase;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("rs_integration.uiSmoke") || event.phase!=TickEvent.Phase.END) return;
        var mc=Minecraft.getInstance();
        if (!opened && mc.player!=null && mc.getSingleplayerServer()!=null) {
            opened=true; mc.options.guiScale().set(2); mc.resizeDisplay();
            var id=mc.player.getUUID();
            mc.getSingleplayerServer().execute(() -> {
                var p=mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var level=p.serverLevel();var pos=new BlockPos(0,101,0);
                level.setBlockAndUpdate(pos.below(),Blocks.MAGMA_BLOCK.defaultBlockState());
                level.removeBlock(pos,false);
                level.setBlockAndUpdate(pos,com.Polarice3.Goety.common.blocks.ModBlocks.BREWING_CAULDRON.get().defaultBlockState());
                p.teleportTo(.5,101,2.5);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(com.refinedmods.refinedstorage.RSItems.CREATIVE_WIRELESS_GRID.get()));
                var hit=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
                CauldronInteraction.rightClick(new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                        p,net.minecraft.world.InteractionHand.MAIN_HAND,pos,hit));
                if(!(p.containerMenu instanceof WitchCauldronMenu m)) throw new AssertionError("Terminal entry failed");
                m.slots.get(0).set(new ItemStack(Items.WATER_BUCKET));
            });
        }
        if(!(mc.screen instanceof WitchCauldronScreen s) || mc.player==null
                || !(mc.player.containerMenu instanceof WitchCauldronMenu m) || m.clientCatalog.isEmpty()) return;
        if(++ticks<30)return;ticks=0;
        switch(phase++) {
            case 0 -> click(mc,s,160,73);
            case 1 -> {
                if(!m.clientState.getBoolean("valid"))throw new AssertionError("Effect selection failed");
                shot(mc,"notebook-effects.png");click(mc,s,319,158);
            }
            case 2 -> click(mc,s,160,73);
            case 3 -> click(mc,s,291,89);
            case 4 -> {
                if(m.clientState.getList("selected",net.minecraft.nbt.Tag.TAG_STRING).size()<2)throw new AssertionError("Enhancement selection failed");
                shot(mc,"notebook-enhancements.png");click(mc,s,319,158);
            }
            case 5 -> {shot(mc,"notebook-materials.png");click(mc,s,45,118);}
            case 6 -> {
                if(m.clientState.getInt("water")!=3 || !m.slots.get(0).getItem().isEmpty())throw new AssertionError("Water exchange failed");
                mc.options.guiScale().set(4);mc.resizeDisplay();
            }
            case 7 -> {shot(mc,"notebook-small.png");click(mc,s,140,158);}
            case 8 -> click(mc,s,160,73);
            case 9 -> click(mc,s,320,135);
            case 10 -> {
                if(m.clientState.getList("selected",net.minecraft.nbt.Tag.TAG_STRING).size()!=1)throw new AssertionError("Scaled remove failed");
                com.huanghuang.rsintegration.feature.goety.GoetyCauldronFeature.LOGGER.info("NOTEBOOK_UI_PASS: terminal entry, effects, enhancement, materials, water exchange, resized navigation");
                OnlineCraftSmoke.begin(mc.getSingleplayerServer(), mc.player.getUUID());
            }
        }
    }
    private static void shot(Minecraft mc,String name) {
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),ignored -> {});
    }
    private static void click(Minecraft mc,WitchCauldronScreen s,int x,int y) {
        double k=Math.min(1,Math.min(mc.getWindow().getGuiScaledWidth()/(double)WitchCauldronMenu.WIDTH,
                mc.getWindow().getGuiScaledHeight()/(double)WitchCauldronMenu.HEIGHT));
        int w=(int)Math.ceil(mc.getWindow().getGuiScaledWidth()/k),h=(int)Math.ceil(mc.getWindow().getGuiScaledHeight()/k);
        double px=((w-WitchCauldronMenu.WIDTH)/2+x)*k,py=((h-WitchCauldronMenu.HEIGHT)/2+y)*k;
        s.mouseClicked(px,py,0);s.mouseReleased(px,py,0);
    }
}
