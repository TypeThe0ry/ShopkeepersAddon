package me.w41k3r.shopkeepersAddon.economy;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.config;
import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.plugin;

public class DailyEarningsManager {

    private static final String LAST_RESET_PATH = "last-reset";
    private static final String EARNINGS_PATH = "earnings";
    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;

    private static File dataFile;
    private static FileConfiguration dataConfig;
    private static String lastResetDate;
    private static long nextDateCheckMillis;
    private static boolean dirty;
    private static BukkitTask saveTask;

    public static void initialize() {
        dataFile = new File(plugin.getDataFolder(), "daily_earnings.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create daily_earnings.yml", e);
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        lastResetDate = dataConfig.getString(LAST_RESET_PATH, "");
        checkDateReset();
        startSaveTask();
    }

    private static void checkDateReset() {
        ensureInitialized();
        long now = System.currentTimeMillis();
        if (now < nextDateCheckMillis) {
            return;
        }

        LocalDate todayDate = LocalDate.now();
        String today = todayDate.toString();
        nextDateCheckMillis = todayDate.plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        if (!today.equals(lastResetDate)) {
            dataConfig.set(EARNINGS_PATH, null);
            dataConfig.set(LAST_RESET_PATH, today);
            lastResetDate = today;
            markDirty();
        }
    }

    public static double getEarnings(Player player) {
        checkDateReset();
        return getEarningsWithoutReset(player);
    }

    public static void addEarnings(Player player, double amount) {
        checkDateReset();
        dataConfig.set(EARNINGS_PATH + "." + player.getUniqueId(), getEarningsWithoutReset(player) + amount);
        markDirty();
    }

    private static double getEarningsWithoutReset(Player player) {
        return dataConfig.getDouble(EARNINGS_PATH + "." + player.getUniqueId(), 0.0D);
    }

    public static boolean isLimitEnabled() {
        return config.getBoolean("economy.daily-earning-limit.enabled", false);
    }

    public static double getDailyLimit() {
        return config.getDouble("economy.daily-earning-limit.limit", 1000.0D);
    }

    public static double getRemainingLimit(Player player) {
        if (!isLimitEnabled()) {
            return Double.MAX_VALUE;
        }
        return Math.max(0.0D, getDailyLimit() - getEarnings(player));
    }

    public static void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        flushDirtyData();
    }

    private static void markDirty() {
        dirty = true;
    }

    private static void startSaveTask() {
        if (plugin == null) {
            return;
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        saveTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                DailyEarningsManager::flushDirtyData,
                SAVE_INTERVAL_TICKS,
                SAVE_INTERVAL_TICKS);
    }

    private static void flushDirtyData() {
        if (!dirty) {
            return;
        }
        ensureInitialized();
        try {
            dataConfig.save(dataFile);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save daily_earnings.yml", e);
        }
    }

    private static void ensureInitialized() {
        if (dataConfig == null) {
            dataFile = new File(plugin.getDataFolder(), "daily_earnings.yml");
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            lastResetDate = dataConfig.getString(LAST_RESET_PATH, "");
        }
    }
}
