package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class BatchQuantityScreen extends Screen {

    private static final int PANEL_BG = 0xFF1E1E2E;
    private static final int BORDER = 0xFF555566;
    private static final int TITLE_COLOR = 0xFFE0E0FF;
    private static final int LABEL_COLOR = 0xFFAAAAAA;

    private final ResourceLocation recipeId;
    @Nullable private final ResourceLocation dim;
    private final BlockPos machinePos;
    private final ModType modType;
    private final String tooltipKey;

    private EditBox quantityInput;
    private String errorMessage;
    private List<ItemStack> displayStacks;
    private int perCraftItems;

    public BatchQuantityScreen(ResourceLocation recipeId, @Nullable ResourceLocation dim,
                               BlockPos machinePos, ModType modType, String tooltipKey) {
        super(Component.translatable("rsi.batch.title",
                Component.translatable(tooltipKey)));
        this.recipeId = recipeId;
        this.dim = dim;
        this.machinePos = machinePos;
        this.modType = modType;
        this.tooltipKey = tooltipKey;
    }

    @Override
    protected void init() {
        loadDisplayStacks();

        int panelW = 220;
        int panelX = (width - panelW) / 2;
        int topY = height / 2 - 80;

        quantityInput = new EditBox(font, panelX + 60, topY + 45, 100, 18,
                Component.translatable("rsi.batch.quantity"));
        quantityInput.setValue("1");
        quantityInput.setFilter(s -> s.matches("\\d*"));
        quantityInput.setMaxLength(3);
        quantityInput.setBordered(true);
        addRenderableWidget(quantityInput);
        quantityInput.setFocused(true);

        addRenderableWidget(Button.builder(Component.translatable("rsi.batch.confirm"), btn -> onConfirm())
                .pos(panelX + 25, topY + 130).size(75, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("rsi.batch.cancel"), btn -> onClose())
                .pos(panelX + 120, topY + 130).size(75, 20).build());
    }

    private void loadDisplayStacks() {
        displayStacks = new ArrayList<>();
        perCraftItems = 0;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Recipe<?> recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) return;
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients != null && !ingredients.isEmpty()) {
            perCraftItems = ingredients.size();
            Map<net.minecraft.world.item.Item, ItemStack> grouped = new LinkedHashMap<>();
            for (Ingredient ing : ingredients) {
                ItemStack[] stacks = ing.getItems();
                if (stacks.length > 0 && !stacks[0].isEmpty()) {
                    ItemStack rep = stacks[0].copyWithCount(1);
                    ItemStack existing = grouped.get(rep.getItem());
                    if (existing != null) {
                        existing.grow(1);
                    } else {
                        grouped.put(rep.getItem(), rep);
                    }
                }
            }
            displayStacks.addAll(grouped.values());
        }
    }

    private int getQuantity() {
        try { return Integer.parseInt(quantityInput.getValue()); }
        catch (NumberFormatException e) { return -1; }
    }

    private void onConfirm() {
        int qty = getQuantity();
        int max = RSIntegrationConfig.BATCH_CRAFT_MAX.get();
        if (qty < 1 || qty > max) {
            errorMessage = Component.translatable("rsi.batch.error.invalid", max).getString();
            return;
        }
        BatchCraftNetworkHandler.CHANNEL.sendToServer(
                new BatchCraftStartPacket(recipeId, dim, machinePos, qty, modType));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        int panelW = 220;
        int panelH = 170;
        int panelX = (width - panelW) / 2;
        int panelY = height / 2 - 80;
        Font font = Minecraft.getInstance().font;

        // Panel
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER);
        g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, BORDER);

        // Title
        Component title = Component.translatable("rsi.batch.title",
                Component.translatable(tooltipKey));
        g.drawCenteredString(font, title, width / 2, panelY + 10, TITLE_COLOR);

        // Divider
        g.fill(panelX + 10, panelY + 22, panelX + panelW - 10, panelY + 23, BORDER);

        // Quantity section
        g.drawString(font, Component.translatable("rsi.batch.quantity"),
                panelX + 14, panelY + 49, LABEL_COLOR);

        // Per-craft label with correct item count
        if (perCraftItems > 0) {
            g.drawString(font, Component.translatable("rsi.batch.per_craft", perCraftItems),
                    panelX + 14, panelY + 75, LABEL_COLOR);

            if (!displayStacks.isEmpty()) {
                int itemX = panelX + 14;
                int itemY = panelY + 90;
                int maxItems = Math.min(displayStacks.size(), 11);
                for (int i = 0; i < maxItems; i++) {
                    ItemStack stack = displayStacks.get(i);
                    g.renderItem(stack, itemX + i * 18, itemY);
                    g.renderItemDecorations(font, stack, itemX + i * 18, itemY);
                }
            }
        }

        // Total
        int qty = getQuantity();
        if (qty > 0 && perCraftItems > 0) {
            g.drawString(font,
                    Component.translatable("rsi.batch.total", perCraftItems * qty),
                    panelX + 14, panelY + 112, 0xFFCCCCFF);
        }

        // Max hint
        int max = RSIntegrationConfig.BATCH_CRAFT_MAX.get();
        g.drawCenteredString(font, Component.translatable("rsi.batch.max_hint", max),
                width / 2 + 60, panelY + 49, 0xFF666666);

        // Error
        if (errorMessage != null) {
            g.drawCenteredString(font, Component.literal(errorMessage).withStyle(s -> s.withColor(0xFFFF5555)),
                    width / 2, panelY + 135, 0);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
