package com.mohistmc.youer;

import com.mohistmc.i18n.i18n;
import com.mohistmc.youer.eventhandler.EventDispatcherRegistry;
import com.mohistmc.youer.feature.ban.BanConfig;
import com.mohistmc.youer.util.VersionInfo;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("youer")
@OnlyIn(Dist.DEDICATED_SERVER)
public class Youer {
    public static final String NAME = "Youer";
    public static final String modid = "youer";
    public static Logger LOGGER = LogManager.getLogger();
    public static i18n i18n;
    public static String version = "1.21.8";
    public static VersionInfo versionInfo;
    public static final boolean DEBUG = Boolean.getBoolean("youer.debug");

    public Youer(IEventBus modEventBus, Dist dist, ModContainer container) {
        bootstrap();
    }

    // Youer start - bootstrap explicitly rather than relying on FML constructing the @Mod class.
    // FML builds a container for the "youer" entry in neoforge.mods.toml but finds no @Mod class for
    // it: the annotation scan of the neoforge mod file does not reach com.mohistmc. CraftServer calls
    // this instead, so the wiring does not depend on that. Idempotent, in case FML does construct us.
    private static boolean bootstrapped = false;

    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        Map<String, String> arguments = new HashMap<>();
        arguments.put("youer", version);
        arguments.put("bukkit", version);
        arguments.put("craftbukkit", version);
        arguments.put("spigot", version);
        arguments.put("neoforge", NeoForgeVersion.getVersion());
        versionInfo = new VersionInfo(arguments);
        EventDispatcherRegistry.init();
        BanConfig.init();
    }
    // Youer end

    public static void initI18n() {
        String mohist_lang = YouerConfig.yml.getString("youer.lang", Locale.getDefault().toString());
        i18n = new i18n(Youer.class.getClassLoader(), mohist_lang);
    }
}