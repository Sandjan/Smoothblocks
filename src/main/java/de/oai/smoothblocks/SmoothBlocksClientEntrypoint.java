package de.oai.smoothblocks;

import net.fabricmc.api.ClientModInitializer;

public final class SmoothBlocksClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SmoothBlocksClient.init();
    }
}
