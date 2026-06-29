package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.refinedmods.refinedstorage.api.network.INetwork;

public abstract class AbstractBatchDelegate implements IBatchDelegate {
    protected ExtractionLedger ledger;
    protected ExtractionLedger sharedLedger;
    protected INetwork network;
    protected boolean usingSharedLedger;

    /** Call at end of onBatchFailed and onBatchFinished — resets all shared state. */
    protected void resetState() {
        ledger = null;
        sharedLedger = null;
        network = null;
        usingSharedLedger = false;
    }
}
