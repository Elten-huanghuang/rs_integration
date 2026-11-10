package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.crafting.plan.ClientRecipeExclusions;
import com.huanghuang.rsintegration.feature.villager.tradelock.client.VillagerTradeLockClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.bookmarks.RecipeBookmark;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JeiBookmarkMutationTest {
    private final BookmarkListChangeMixin handler = new BookmarkListChangeMixin() {};

    @Test
    void successfulRemovalClearsTheOriginalRecipesExclusion() throws Exception {
        RecipeBookmark<?, ?> bookmark = mock(RecipeBookmark.class);
        ResourceLocation recipeId = new ResourceLocation("embers", "ashen_cloth");
        when(bookmark.getRecipeUid()).thenReturn(recipeId);
        Operation<Boolean> original = operation(bookmark, true);
        try (var trades = mockStatic(VillagerTradeLockClient.class);
             var exclusions = mockStatic(ClientRecipeExclusions.class)) {
            assertTrue(invoke("rsIntegration$bookmarkRemoved", bookmark, original));
            verify(original).call(bookmark);
            verifyNoMoreInteractions(original);
            trades.verify(VillagerTradeLockClient::markDirty);
            exclusions.verify(() -> ClientRecipeExclusions.clear(recipeId));
        }
    }

    @Test
    void failedMutationsKeepPreferencesAndTradeLocksUnchanged() throws Exception {
        IBookmark bookmark = mock(IBookmark.class);
        try (var trades = mockStatic(VillagerTradeLockClient.class);
             var exclusions = mockStatic(ClientRecipeExclusions.class)) {
            for (String method : new String[]{"rsIntegration$bookmarkAdded", "rsIntegration$bookmarkRemoved"}) {
                Operation<Boolean> original = operation(bookmark, false);
                assertFalse(invoke(method, bookmark, original));
                verify(original).call(bookmark);
                verifyNoMoreInteractions(original);
            }
            trades.verifyNoInteractions();
            exclusions.verifyNoInteractions();
        }
    }

    @Test
    void successfulAdditionAndItemRemovalOnlyRefreshTradeLocks() throws Exception {
        IBookmark bookmark = mock(IBookmark.class);
        for (String method : new String[]{"rsIntegration$bookmarkAdded", "rsIntegration$bookmarkRemoved"}) {
            Operation<Boolean> original = operation(bookmark, true);
            try (var trades = mockStatic(VillagerTradeLockClient.class);
                 var exclusions = mockStatic(ClientRecipeExclusions.class)) {
                assertTrue(invoke(method, bookmark, original));
                verify(original).call(bookmark);
                verifyNoMoreInteractions(original);
                trades.verify(VillagerTradeLockClient::markDirty);
                exclusions.verifyNoInteractions();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Operation<Boolean> operation(IBookmark bookmark, boolean result) {
        Operation<Boolean> operation = mock(Operation.class);
        when(operation.call(bookmark)).thenReturn(result);
        return operation;
    }

    private boolean invoke(String method, IBookmark bookmark, Operation<Boolean> original) throws Exception {
        var callback = BookmarkListChangeMixin.class.getDeclaredMethod(method, IBookmark.class, Operation.class);
        callback.setAccessible(true);
        return (boolean) callback.invoke(handler, bookmark, original);
    }
}
