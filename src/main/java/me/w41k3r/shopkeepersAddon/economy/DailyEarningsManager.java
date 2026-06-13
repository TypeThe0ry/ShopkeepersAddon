package me.w41k3r.shopkeepersAddon.economy;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.config;
import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.plugin;

public class DailyEarningsManager {

    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String LAST_RESET_PATH = "last-reset";
    private static final String EARNINGS_PATH = "earnings";

    private static File dataFile;
    private static FileConfiguration dataConfig;
    private static String lastResetDate;

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
    }

    private static void checkDateReset() {
        ensureInitialized();
        String today = new SimpleDateFormat(DATE_FORMAT).format(new Date());
        if (!today.equals(lastResetDate)) {
            dataConfig.set(EARNINGS_PATH, null);
            dataConfig.set(LAST_RESET_PATH, today);
            lastResetDate = today;
            saveData();
        }
    }

    public static double getEarnings(Player player) {
        checkDateReset();
        return getEarningsWithoutReset(player);
    }

    public static void addEarnings(Player player, double amount) {
        checkDateReset();
        dataConfig.set(EARNINGS_PATH + "." + player.getUniqueId(), getEarningsWithoutReset(player) + amount);
        saveData();
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

    private static void saveData() {
        ensureInitialized();
        try {
            dataConfig.save(dataFile);
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
