package dev.fastrecipes;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class FastRecipes implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        FastRecipesConfig.current();
    }
}
