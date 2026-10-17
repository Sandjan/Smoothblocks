package de.oai.smoothblocks;

import net.fabricmc.api.ClientModInitializer;

/** Fabric client entry point; rendering hooks create GPU resources lazily. */
public final class SmoothBlocksClientEntrypoint implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SmoothBlocksClient.init();
    }
}
