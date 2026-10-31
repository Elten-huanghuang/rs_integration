package com.huanghuang.rsintegration.feature.goety;

import com.huanghuang.rsintegration.api.RSIIntegrationApi;
import com.huanghuang.rsintegration.api.RegistrationOptions;
import com.huanghuang.rsintegration.feature.goety.integration.GoetyCauldronMachine;

final class GoetyRsiRegistrations {
    private GoetyRsiRegistrations() {}

    static void register(RSIIntegrationApi api) {
        var result = api.registerMachine(new GoetyCauldronMachine(), RegistrationOptions.defaults());
        if (!result.accepted()) throw new IllegalStateException("Goety RSI machine registration rejected: " + result);
    }
}
