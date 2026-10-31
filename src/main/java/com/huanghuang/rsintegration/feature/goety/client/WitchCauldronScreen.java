package com.huanghuang.rsintegration.feature.goety.client;

import com.huanghuang.rsintegration.feature.goety.brewing.BrewCatalog;
import com.huanghuang.rsintegration.feature.goety.menu.WitchCauldronMenu;
import com.huanghuang.rsintegration.feature.goety.network.BrewNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** A three-page brewing notebook. All actions remain server-authoritative. */
public final class WitchCauldronScreen extends AbstractContainerScreen<WitchCauldronMenu> {
    private static final int INK = 0x493C2B, MUTED = 0x827258, GREEN = 0x4B6654;
    private static final ResourceLocation PAPER = ResourceLocation.parse("rs_integration:textures/gui/notebook.png");
    private final List<ResourceLocation> selected = new ArrayList<>();
    private List<BrewCatalog.Entry> catalog = List.of();
    private List<BrewCatalog.Group> effects = List.of(), modifiers = List.of();
    private List<BrewCatalog.Group> filtered = List.of();
    private int page, offset, needsOffset, accepted = -1, levelOffset;
    private BrewCatalog.Group focus;
    private EditBox search;
    private Button back, next, feed, craft, stir, bind, clear, remove;
    private final List<Button> levels = new ArrayList<>();
    private double scale = 1;
    private String query = "";

    public WitchCauldronScreen(WitchCauldronMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WitchCauldronMenu.WIDTH; imageHeight = WitchCauldronMenu.HEIGHT;
    }
    private Component tr(String key) { return WitchCauldronMenu.text(key); }
    private Button add(String key, int x, int y, int w, Runnable action) {
        var b = new Button(leftPos + x, topPos + y, w, 18, tr(key), ignored -> action.run(), n -> n.get()) {
            @Override public void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                boolean primary = this == next || this == feed;
                int bg = primary ? (isHoveredOrFocused() ? 0xFF577762 : 0xFF405D4D)
                        : (isHoveredOrFocused() && active ? 0xFFF4E7CB : 0xFFE5D5B5);
                if (!active) bg = 0xFFD8CFBB;
                g.fill(getX(), getY()+1, getX()+width, getY()+height, 0x55453624);
                g.fill(getX()+1, getY(), getX()+width-1, getY()+height-1, bg);
                g.fill(getX()+2, getY(), getX()+width-2, getY()+1, primary ? 0xFF8DA38A : 0xFFFFF1D8);
                var label = font.plainSubstrByWidth(getMessage().getString(), width - 6);
                g.drawString(font, label, getX()+(width-font.width(label))/2, getY()+5,
                        !active ? MUTED : primary ? 0xF6EEDB : INK, false);
            }
        };
        return addRenderableWidget(b);
    }
    @Override protected void init() {
        scale = Math.min(1, Math.min(width / (double) imageWidth, height / (double) imageHeight));
        width = (int)Math.ceil(width/scale); height = (int)Math.ceil(height/scale);
        super.init();
        search = new EditBox(font, leftPos+115, topPos+45, 242, 14, tr("notebook.search"));
        search.setBordered(false); search.setTextColor(INK); search.setHint(tr("notebook.search"));
        search.setMaxLength(64); search.setValue(query);
        search.setResponder(s -> { query = s; offset = 0; filter(); });
        addRenderableWidget(search);
        back = add("notebook.back", 112, 151, 63, () -> changePage(page-1));
        next = add("notebook.next", 283, 151, 77, () -> changePage(page+1));
        levels.clear();
        for (int i=0;i<4;i++) {
            final int index=i;
            levels.add(add("plus", 277+i%2*43, 81+i/2*23, 39, () -> {
                if (focus == null) return;
                var tiers=focus.levels();
                if (levelOffset+index < tiers.size()) selectLevel(tiers.get(levelOffset+index));
            }));
        }
        remove = add("remove_modifier", 277, 127, 82, () -> {
            if (focus != null) { selected.removeIf(id -> belongs(id,focus)); send("preview"); }
        });
        add("add_water", 9, 110, 78, () -> send("water"));
        add("bottle", 9, 132, 78, () -> send("bottle"));
        bind = add("bind_button", 9, 160, 78, () -> send(menu.clientState.getBoolean("bound") ? "unbind" : "bind"));
        clear = add("clear_failed", 9, 183, 78, () -> send("clear"));
        craft = add("craft_missing", 277, 193, 94, () -> send("craft"));
        feed = add("feed", 277, 216, 94, () -> send("feed"));
        stir = add("stir", 277, 239, 94, () -> send("stir"));
        filter();
    }
    private void changePage(int p) {
        if (p<0 || p>2 || p>0 && !hasEffect()) return;
        page=p; offset=0; needsOffset=0; focus=null; levelOffset=0;
        query=""; search.setValue(""); filter();
    }
    private boolean belongs(ResourceLocation id, BrewCatalog.Group group) {
        return group.entries().stream().anyMatch(e -> e.item().equals(id));
    }
    private boolean chosen(BrewCatalog.Group group) { return selected.stream().anyMatch(id -> belongs(id,group)); }
    private boolean hasEffect() { return effects.stream().anyMatch(this::chosen); }
    private int tier(BrewCatalog.Group group) {
        return group.entries().stream().filter(e -> selected.contains(e.item())).mapToInt(BrewCatalog.Entry::level).findFirst().orElse(-1);
    }
    private void selectLevel(int tier) {
        var option=focus.entries().stream().filter(e -> e.level()==tier).findFirst().orElseThrow();
        var shapes=Set.of("splash","lingering","gas");
        selected.removeIf(id -> belongs(id,focus) || shapes.contains(focus.key()) && catalog.stream()
                .anyMatch(e -> e.item().equals(id) && shapes.contains(e.group())));
        selected.add(option.item()); send("preview");
    }
    private void send(String action) {
        menu.clientRevision++;
        BrewNetwork.CHANNEL.sendToServer(new BrewNetwork.Edit(menu.containerId,menu.clientRevision,-1,List.copyOf(selected),action));
    }
    private void filter() {
        String q=query.toLowerCase(Locale.ROOT);
        filtered=(page==0?effects:modifiers).stream().filter(g -> g.name().getString().toLowerCase(Locale.ROOT).contains(q)
                || g.entries().stream().anyMatch(e -> e.item().toString().contains(q)
                || e.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q))).toList();
    }
    private void update() {
        if (catalog != menu.clientCatalog) {
            catalog=menu.clientCatalog; effects=BrewCatalog.groups(catalog,BrewCatalog.EFFECT);
            modifiers=BrewCatalog.groups(catalog,BrewCatalog.MODIFIER); filter();
        }
        var s=menu.clientState; int rev=s.getInt("revision");
        if (rev==menu.clientRevision && accepted!=rev && s.contains("selected")) {
            selected.clear();
            for (var id:s.getList("selected",Tag.TAG_STRING)) selected.add(ResourceLocation.parse(id.getAsString()));
            accepted=rev;
        }
        boolean ready=rev==menu.clientRevision && !s.getBoolean("ordersBusy");
        if (!hasEffect() && page>0) changePage(0);
        search.visible=page<2; search.setEditable(ready);
        back.visible=page>0; back.active=ready;
        next.visible=page<2; next.active=ready && hasEffect();
        bind.active=ready; bind.setMessage(tr(s.getBoolean("bound")?"unbind_button":"bind_button"));
        clear.active=ready && s.getString("mode").equals("FAILED");
        craft.active=page==2 && ready && s.getBoolean("valid") && s.getBoolean("online");
        feed.active=page==2 && ready && s.getBoolean("valid");
        stir.active=ready;
        var tiers=focus==null?List.<Integer>of():focus.levels();
        levelOffset=Math.min(levelOffset, Math.max(0,tiers.size()-4));
        for(int i=0;i<levels.size();i++) {
            var b=levels.get(i); b.visible=page==1 && focus!=null && i+levelOffset<tiers.size(); b.active=ready;
            if(b.visible) { int t=tiers.get(i+levelOffset); b.setMessage(Component.literal((tier(focus)==t?"• ":"")+(t+1))); }
        }
        remove.visible=page==1 && focus!=null; remove.active=ready && focus!=null && chosen(focus);
        offset=Math.min(offset,Math.max(0,filtered.size()-4));
    }
    private void text(GuiGraphics g, Component t, int x,int y,int color) { g.drawString(font,t,leftPos+x,topPos+y,color,false); }
    private void text(GuiGraphics g, String t, int x,int y,int color) { g.drawString(font,t,leftPos+x,topPos+y,color,false); }
    private void rect(GuiGraphics g,int x,int y,int w,int h,int color) { g.fill(leftPos+x,topPos+y,leftPos+x+w,topPos+y+h,color); }
    @Override protected void renderBg(GuiGraphics g,float dt,int mx,int my) {
        update();
        // Separate utility card, paper and inventory tray; no giant enclosing panel.
        rect(g,4,12,90,249,0x50302012); rect(g,2,10,90,249,0xFFE8DDC7);
        rect(g,3,11,88,1,0xFFFFF2DA);
        g.blit(PAPER,leftPos+96,topPos,0,0,284,180,284,180);
        rect(g,96,185,172,78,0xDD403C32);
        rect(g,273,185,102,78,0xE2D6C8AC);
        text(g,tr("notebook.tools"),10,19,INK);
        text(g,tr("notebook.water"),10,33,MUTED);
        text(g,menu.clientState.getInt("water")+"/3",45,48,GREEN);
        var preview=ItemStack.of(menu.clientState.getCompound("output"));
        if(!preview.isEmpty()) g.renderItem(preview,leftPos+68,topPos+44);
        text(g,tr("notebook.bottling"),10,72,MUTED); text(g,"→",45,90,MUTED);
        for(var slot:menu.slots) {
            rect(g,slot.x-1,slot.y-1,18,18,0xFF9B8969);
            rect(g,slot.x,slot.y,16,16,slot.index<3?0xFFF6ECD5:0xFFE2D8C1);
            rect(g,slot.x,slot.y,16,1,0xFFB7A788);
        }
        text(g,"0"+(page+1),113,17,GREEN);
        text(g,tr("notebook.page."+page),140,17,INK);
        text(g,(page+1)+" / 3",333,18,MUTED);
        rect(g,112,33,248,1,0x55877552);
        if(page<2) {
            rect(g,112,43,248,17,0x55B49A6B);
            for(int i=0;i<4 && offset+i<filtered.size();i++) {
                var group=filtered.get(offset+i); int y=66+i*20; int w=page==0?248:157;
                boolean chosen=chosen(group);
                if(chosen || group==focus) rect(g,112,y,w,19,chosen?0x445C8058:0x33A98B54);
                rect(g,112,y+19,w,1,0x228A7350);
                var e=group.entries().get(0); g.renderItem(e.stack(),leftPos+115,topPos+y+1);
                text(g,font.plainSubstrByWidth(group.name().getString(),w-49),136,y+2,INK);
                text(g,page==0?tr("notebook.auto_material").getString():tr("level").getString()+" "+(tier(group)<0?"—":tier(group)+1),136,y+11,MUTED);
                if(chosen) text(g,"•",112+w-11,y+5,GREEN);
            }
            if(filtered.isEmpty()) text(g,tr("notebook.empty"),120,78,MUTED);
            if(filtered.size()>4) {
                int thumb= Math.max(8,80*4/filtered.size());
                rect(g,page==0?362:270,66,1,80,0x44877552);
                rect(g,page==0?362:270,66+(80-thumb)*offset/Math.max(1,filtered.size()-4),2,thumb,0xFF887552);
            }
            if(page==1 && focus==null) g.drawWordWrap(font,tr("notebook.pick_modifier"),leftPos+282,topPos+78,73,MUTED);
            if(page==1 && focus!=null) text(g,tr("select_level"),281,68,MUTED);
        } else {
            text(g,Component.translatable("gui.rs_integration.auto_capacity",menu.clientState.getInt("capacity"),menu.clientState.getInt("used")),114,44,GREEN);
            var needs=menu.clientState.getList("needs",Tag.TAG_COMPOUND);
            needsOffset=Math.min(needsOffset,Math.max(0,needs.size()-4));
            for(int i=0;i<4 && needsOffset+i<needs.size();i++) {
                var n=needs.getCompound(needsOffset+i); int y=64+i*20; var item=ItemStack.of(n);
                g.renderItem(item,leftPos+115,topPos+y);
                text(g,font.plainSubstrByWidth(item.getHoverName().getString(),162),136,y+4,INK);
                String count=n.getLong("available")+"/"+n.getInt("needed");
                text(g,count,357-font.width(count),y+4,n.getLong("available")>=n.getInt("needed")?GREEN:0xA05D3F);
                rect(g,112,y+18,248,1,0x228A7350);
            }
        }
        int chosen=(int)effects.stream().filter(this::chosen).count();
        text(g,Component.translatable("gui.rs_integration.notebook.chosen",chosen),181,156,MUTED);
        var s=menu.clientState;
        text(g,tr(s.getBoolean("online")?"network_online":"network_offline"),10,211,MUTED);
        text(g,tr(s.getBoolean("heated")?"hot":"cold"),10,225,MUTED);
        text(g,tr("souls").getString()+": "+s.getInt("souls"),10,239,MUTED);
        text(g,font.plainSubstrByWidth(tr("mode."+s.getString("mode")).getString(),78),10,251,MUTED);
        // Single-line status with full text on hover; long errors never spill outside the window.
        if(s.contains("message")) {
            var message=Component.Serializer.fromJson(s.getString("message"));
            if(message!=null) text(g,font.plainSubstrByWidth(message.getString(),360),10,269,0xEDE2CA);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y) { }
    @Override public boolean mouseClicked(double x,double y,int button) {
        x/=scale; y/=scale; int px=(int)x-leftPos, py=(int)y-topPos;
        if(button==0 && page<2 && !menu.clientState.getBoolean("ordersBusy") && px>=112 && px<(page==0?360:269) && py>=66 && py<146) {
            int i=offset+(py-66)/20;
            if(i<filtered.size()) {
                focus=filtered.get(i); levelOffset=0;
                if(page==0) {
                    if(chosen(focus)) selected.removeIf(id->belongs(id,focus));
                    else selected.add(focus.entries().get(0).item());
                    send("preview");
                }
                return true;
            }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double d) {
        int px=(int)(x/scale)-leftPos,py=(int)(y/scale)-topPos; int delta=d>0?-1:1;
        if(px>=112 && px<=366 && py>=64 && py<146) {
            if(page==2) needsOffset=Math.max(0,needsOffset+delta);
            else if(page==1 && px>=277) levelOffset=Math.max(0,levelOffset+delta);
            else offset=Math.max(0,offset+delta);
            return true;
        }
        return super.mouseScrolled(x/scale,y/scale,d);
    }
    @Override public boolean keyPressed(int key,int scan,int mods) {
        if(search.visible && search.isFocused() && key!=256) {
            if(search.keyPressed(key,scan,mods)) return true;
            if(minecraft.options.keyInventory.matches(key,scan)) return true;
        }
        return super.keyPressed(key,scan,mods);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float dt) {
        renderBackground(g);
        g.pose().pushPose(); g.pose().scale((float)scale,(float)scale,1);
        super.render(g,(int)(mx/scale),(int)(my/scale),dt);
        g.pose().popPose();
        // Container slot hover is computed in logical coordinates by super.render.
        renderTooltip(g,mx,my);
        int x=(int)(mx/scale)-leftPos,y=(int)(my/scale)-topPos;
        if(x>=68 && x<84 && y>=44 && y<60) {
            var preview=ItemStack.of(menu.clientState.getCompound("output"));
            if(!preview.isEmpty()) g.renderTooltip(font,preview,mx,my);
        } else if(y>=267 && y<281 && menu.clientState.contains("message")) {
            var c=Component.Serializer.fromJson(menu.clientState.getString("message"));
            if(c!=null) g.renderComponentTooltip(font,List.of(c),mx,my);
        } else if(page<2 && x>=112 && x<(page==0?360:269) && y>=66 && y<146) {
            int i=offset+(y-66)/20;
            if(i<filtered.size()) {
                var group=filtered.get(i); var tips=new ArrayList<Component>(); tips.add(group.name());
                tips.add(tr("alternatives_help"));
                group.entries().stream().limit(6).forEach(e->tips.add(Component.translatable("gui.rs_integration.material_stock",
                        e.stack().getHoverName(),menu.clientState.getCompound("stocks").getLong(e.item().toString()),1)));
                g.renderComponentTooltip(font,tips,mx,my);
            }
        } else if(page==2 && x>=112 && x<=360 && y>=64 && y<144) {
            var needs=menu.clientState.getList("needs",Tag.TAG_COMPOUND); int i=needsOffset+(y-64)/20;
            if(i<needs.size()) g.renderTooltip(font,ItemStack.of(needs.getCompound(i)),mx,my);
        }
    }
    @Override public boolean mouseReleased(double x,double y,int b) { return super.mouseReleased(x/scale,y/scale,b); }
    @Override public boolean mouseDragged(double x,double y,int b,double dx,double dy) { return super.mouseDragged(x/scale,y/scale,b,dx/scale,dy/scale); }
    @Override public void mouseMoved(double x,double y) { super.mouseMoved(x/scale,y/scale); }
}
