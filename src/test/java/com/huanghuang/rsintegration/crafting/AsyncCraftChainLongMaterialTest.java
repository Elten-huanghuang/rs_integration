package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncCraftChainLongMaterialTest extends BootstrapTest {
    @Test
    void splitsLongBrokerDemandWithoutChangingTotal() {
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        long requested = (long) Integer.MAX_VALUE + 5L;
        List<MaterialBroker.Request> requests = new ArrayList<>();

        AsyncCraftChain.addBrokerRequests(requests, source, diamond, requested);

        assertEquals(1, requests.size());
        assertEquals(requested, requests.get(0).quantity());
        assertEquals(requested, requests.stream()
                .mapToLong(MaterialBroker.Request::quantity).sum());
    }
}
