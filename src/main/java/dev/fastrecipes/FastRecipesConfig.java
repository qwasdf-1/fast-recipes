package dev.fastrecipes;

import com.mojang.logging.LogUtils;
import dev.fastrecipes.experiment.CraftingLookup.Mode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.tomlj.Toml;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlTable;

/** Startup settings shared by the server entrypoint and runtime mixins. */
public record FastRecipesConfig(
        Mode lookup, int craftingCacheEntries, boolean batchCrafting, boolean smithingIndex,
        boolean stonecuttingIndex, boolean furnaceHits, boolean furnaceMisses) {
    private static final String DEFAULT_RESOURCE = "/fast-recipes.default.toml";
    private static final FastRecipesConfig DEFAULTS =
            new FastRecipesConfig(Mode.OFF, 1, false, false, false, false, false);

    public static FastRecipesConfig current() {
        return Startup.CONFIG;
    }

    private static final class Startup {
        private static final FastRecipesConfig CONFIG =
                load(FabricLoader.getInstance().getConfigDir().resolve("fast-recipes.toml"));
    }

    static FastRecipesConfig load(Path path) {
        FastRecipesConfig config = DEFAULTS;
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                try (var template = FastRecipesConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (template == null) {
                        throw new IOException("Bundled config template is missing");
                    }
                    try {
                        Files.copy(template, path);
                    } catch (FileAlreadyExistsException ignored) {
                        // A concurrent start created it first.
                    }
                }
            }
            var table = Toml.parse(path);
            if (table.hasErrors()) {
                throw new IllegalArgumentException(table.errors().toString());
            }
            config = parse(table);
        } catch (IOException | IllegalArgumentException | TomlInvalidTypeException error) {
            LogUtils.getLogger().warn("Could not read Fast Recipes config {}. Using defaults before JVM overrides. {}",
                    path, error.getMessage());
        }
        return config.withOverrides();
    }

    private static FastRecipesConfig parse(TomlTable table) {
        String mode = table.getString("lookup", () -> "off");
        Mode lookup = Mode.parse(mode);
        if (!lookup.id().equals(mode)) {
            throw new IllegalArgumentException("Unknown lookup mode: " + mode);
        }
        long entries = table.getLong("crafting_cache_entries", () -> 1);
        if (entries < 1 || entries > 16) {
            throw new IllegalArgumentException("crafting_cache_entries must be between 1 and 16");
        }
        return new FastRecipesConfig(lookup, (int) entries, flag(table, "batch_crafting"),
                flag(table, "smithing_index"), flag(table, "stonecutting_index"),
                flag(table, "furnace_hits"), flag(table, "furnace_misses"));
    }

    private static boolean flag(TomlTable table, String key) {
        return table.getBoolean(key, () -> false);
    }

    private FastRecipesConfig withOverrides() {
        return new FastRecipesConfig(Mode.parse(System.getProperty("fastrecipes.lookup", lookup.id())),
                Math.clamp(Integer.getInteger("fastrecipes.craftingCacheEntries", craftingCacheEntries), 1, 16),
                override("batchCrafting", batchCrafting), override("smithingIndex", smithingIndex),
                override("stonecuttingIndex", stonecuttingIndex), override("furnaceHits", furnaceHits),
                override("furnaceMisses", furnaceMisses));
    }

    private static boolean override(String name, boolean fallback) {
        return Boolean.parseBoolean(System.getProperty("fastrecipes." + name, Boolean.toString(fallback)));
    }
}
