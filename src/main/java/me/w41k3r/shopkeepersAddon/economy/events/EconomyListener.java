package me.w41k3r.shopkeepersAddon.economy.events;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.events.ShopkeeperOpenUIEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import com.nisovin.shopkeepers.api.shopkeeper.admin.AdminShopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.player.PlayerShopkeeper;

import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.config;
import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.debugLog;
import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.plugin;
import static me.w41k3r.shopkeepersAddon.ShopkeepersAddon.sendPlayerMessage;
import me.w41k3r.shopkeepersAddon.economy.EconomyManager;
import static me.w41k3r.shopkeepersAddon.economy.EconomyManager.formatPrice;
import static me.w41k3r.shopkeepersAddon.economy.EconomyManager.hasMoney;
import static me.w41k3r.shopkeepersAddon.economy.PersistantDataManager.getPrice;
import static me.w41k3r.shopkeepersAddon.economy.PersistantDataManager.isEconomyItem;
import me.w41k3r.shopkeepersAddon.economy.DailyEarningsManager;
import me.w41k3r.shopkeepersAddon.economy.objects.ShopEditTask;
import static me.w41k3r.shopkeepersAddon.gui.managers.Utils.removeEconomyItem;
import static me.w41k3r.shopkeepersAddon.gui.managers.Utils.setItemsOnTradeSlots;

public class EconomyListener implements Listener {

    private static final int REMOVE_ECONOMY_ITEM_DELAY = 1;
    private static final String BUY_SUCCESS_FALLBACK = "§aYou have bought %item% for %price%.";
    private static final String SELL_SUCCESS_FALLBACK = "§aYou have sold %item% for %price%.";
    private static final String DAILY_LIMIT_FALLBACK = "§cDaily earning limit reached! You can only earn %remaining% more today (Limit: %limit%).";
    private static final String ERROR_FALLBACK = "§cAn error occurred while processing the transaction.";
    private static final String INVENTORY_FULL_FALLBACK = "§cYou don't have enough inventory space for this trade.";
    private static final String NO_MONEY_OWNER_FALLBACK = "§cThe shop owner doesn't have enough money!";

    private static final String SHOPKEEPERS_DATA_PATH = "Shopkeepers/data/save.yml";

    private static final String OWNER_UUID_PATH = ".owner uuid";
    private static final long OWNER_CACHE_REFRESH_INTERVAL_MILLIS = 5000L;

    private static final ConcurrentMap<Class<?>, Optional<Method>> META_GET_AS_STRING_METHODS = new ConcurrentHashMap<>();

    private final Map<String, String> ownerUuidByShopkeeperId = new ConcurrentHashMap<>();
    private long ownerCacheLastModified = Long.MIN_VALUE;
    private long nextOwnerCacheRefreshMillis;

    private enum TradeProcessResult {
        SUCCESS,
        HANDLED_FAILURE,
        UNRECOVERABLE_FAILURE
    }

    @EventHandler
    public void onOpenEditorUI(ShopkeeperOpenUIEvent event) {
        if (!config.getBoolean("economy.enabled", false) ||
                !event.getUIType().equals(ShopkeepersAPI.getDefaultUITypes().getEditorUIType())) {
            return;
        }

        debugLog("Shopkeeper opened editor UI: " + event.getUIType());
        ShopEditTask shopEditor = new ShopEditTask(event.getPlayer(), event.getShopkeeper());
        shopEditor.startEdit();
    }

    @EventHandler
    public void onTradeSelect(TradeSelectEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        scheduleRemoveEconomyItem(player);

        MerchantRecipe recipe = event.getMerchant().getRecipe(event.getIndex());
        if (recipe == null || recipe.getIngredients().isEmpty())
            return;

        ItemStack firstIngredient = recipe.getIngredients().get(0);
        if (!isEconomyItem(firstIngredient) && !isEconomyItem(recipe.getResult())) {
            scheduleRemoveEconomyItem(player);
            return;
        }

        debugLog("Recipe ingredient meta: "
                + (firstIngredient.hasItemMeta() ? firstIngredient.getItemMeta().toString() : "No meta"));

        if (isEconomyItem(firstIngredient)) {
            if (!hasMoney(player, getPrice(firstIngredient))) {
                
                // Show red 'X' lock visual
                recipe.setUses(recipe.getMaxUses());
                
                sendPlayerMessage(player, config.getString("messages.noMoney", "You don't have enough money!"));
                event.setCancelled(true);
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> setItemsOnTradeSlots(event, 0), 5L);
        } else if (isEconomyItem(recipe.getResult())) {
            // Check Owner Balance for Selling
             Shopkeeper shopkeeper = ShopkeepersAPI.getUIRegistry().getUISession(player).getShopkeeper();
             if (shopkeeper instanceof PlayerShopkeeper) {
                 double price = getPrice(recipe.getResult());
                 if (getOwnerMoney(shopkeeper) < price) {
                     sendNoMoneyOwner(player, recipe);
                     event.setCancelled(true);
                     return;
                 }
             } else if (shopkeeper instanceof AdminShopkeeper && DailyEarningsManager.isLimitEnabled()) {
                 double price = getPrice(recipe.getResult());
                 if (DailyEarningsManager.getRemainingLimit(player) < price) {
                     double remaining = DailyEarningsManager.getRemainingLimit(player);
                     
                     // Show red 'X' lock visual
                     recipe.setUses(recipe.getMaxUses());
                     
                     sendPlayerMessage(player, config.getString("messages.dailyLimitReached", DAILY_LIMIT_FALLBACK)
                             .replace("%limit%", formatPrice(DailyEarningsManager.getDailyLimit()))
                             .replace("%remaining%", formatPrice(remaining)));
                     event.setCancelled(true);
                     return;
                 }
             }
        }
    }

    @EventHandler
    public void onShopkeeperTrade(ShopkeeperTradeEvent event) {
        TradingRecipe recipe = event.getTradingRecipe();
        InventoryClickEvent clickEvent = event.getClickEvent();
        if (isEconomyItem(recipe.getResultItem().copy())) {
            event.setCancelled(true);
            boolean processed = false;
            String failureReason = null;
            if (clickEvent != null) {
                Inventory merchantInventory = getMerchantInventory(clickEvent);
                if (merchantInventory instanceof MerchantInventory merchant) {
                    MerchantRecipe selectedRecipe = merchant.getSelectedRecipe();
                    if (selectedRecipe != null) {
                        processEconomyTradeClick(clickEvent, event.getPlayer(), selectedRecipe, recipe);
                        processed = true;
                    } else {
                        failureReason = "merchant inventory did not have a selected recipe";
                    }
                } else {
                    failureReason = "click event did not expose a merchant inventory";
                }
            } else {
                MerchantInventory merchantInventory = getOpenMerchantInventory(event.getPlayer());
                if (merchantInventory != null) {
                    MerchantRecipe selectedRecipe = merchantInventory.getSelectedRecipe();
                    TradeProcessResult result = processEconomyTradeFallback(event.getPlayer(), event.getShopkeeper(),
                            merchantInventory, selectedRecipe, recipe);
                    if (result != TradeProcessResult.UNRECOVERABLE_FAILURE) {
                        processed = true;
                    } else {
                        failureReason = "fallback merchant trade processing failed";
                    }
                } else {
                    failureReason = "trade event did not include a click event and no open merchant inventory was found";
                }
            }
            if (!processed) {
                sendPlayerMessage(event.getPlayer(), config.getString("messages.error", ERROR_FALLBACK));
                debugLog("Cancelled economy-result trade without processing it: " + failureReason);
            }
            debugLog("Handled trade with economy item as result.");
            return;
        }
        debugLog("Trading now!");

        debugLog("Processing shopkeeper trade!");
        Player player = event.getPlayer();
        Shopkeeper shopkeeper = event.getShopkeeper();

        if (clickEvent != null && clickEvent.isShiftClick()) {
            // Only process bulk trade if it is an economy trade (Buying)
            if (isEconomyItem(recipe.getItem1().copy())) {
                event.setCancelled(true);
                processBulkTrade(player, shopkeeper, recipe, clickEvent, getInputTradeCount(clickEvent));
                return;
            }
        }

        if (isEconomyItem(recipe.getItem1().copy())) {
            if (clickEvent == null) {
                MerchantInventory merchantInventory = getOpenMerchantInventory(player);
                TradeProcessResult result = merchantInventory == null
                        ? TradeProcessResult.UNRECOVERABLE_FAILURE
                        : processEconomyTradeFallback(player, shopkeeper, recipe, merchantInventory);
                if (result == TradeProcessResult.UNRECOVERABLE_FAILURE) {
                    event.setCancelled(true);
                    sendPlayerMessage(player, config.getString("messages.error", ERROR_FALLBACK));
                    debugLog("Cancelled economy-input trade without processing it: "
                            + (merchantInventory == null
                                    ? "trade event did not include a click event and no open merchant inventory was found"
                                    : "fallback merchant trade processing failed"));
                    return;
                }
                if (result == TradeProcessResult.HANDLED_FAILURE) {
                    event.setCancelled(true);
                    return;
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                   if (ShopkeepersAPI.getUIRegistry().getUISession(player) != null) {
                        updateTradeSlotsPostTrade(player, recipe, shopkeeper);
                   }
                }, 1L);
                return;
            }
            if (!processEconomyTrade(player, shopkeeper, recipe, clickEvent)) {
                event.setCancelled(true);
            } else {
                // Post-Trade UI Update
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                   // Refresh trade slots? We need context if we are still in UI
                   if (ShopkeepersAPI.getUIRegistry().getUISession(player) != null) {
                       // We can't easily trigger TradeSelectEvent, but we can simulate the "put back" logic
                        updateTradeSlotsPostTrade(player, recipe, shopkeeper);
                   }
                }, 1L);
            }
        }
    }

    private TradeProcessResult processEconomyTradeFallback(Player player, Shopkeeper shopkeeper, TradingRecipe recipe,
            MerchantInventory merchantInventory) {
        double price = getPrice(recipe.getItem1().copy());

        if (!hasMoney(player, price)) {
            sendPlayerMessage(player, config.getString("messages.noMoney", "You don't have enough money!"));
            return TradeProcessResult.HANDLED_FAILURE;
        }

        if (!scheduleSecondIngredientCleanup(merchantInventory, recipe, 1)) {
            return TradeProcessResult.UNRECOVERABLE_FAILURE;
        }

        EconomyManager.takeMoney(player.getName(), price);

        if (!(shopkeeper instanceof AdminShopkeeper)) {
            depositToShopOwner(shopkeeper, price);
        }
        sendPlayerMessage(player, config.getString("messages.buySuccess", BUY_SUCCESS_FALLBACK)
                .replace("%item%", getItemDisplayNameSafe(recipe.getResultItem()))
                .replace("%price%", formatPrice(price)));
        return TradeProcessResult.SUCCESS;
    }

    private boolean processEconomyTrade(Player player, Shopkeeper shopkeeper, TradingRecipe recipe,
            InventoryClickEvent clickEvent) {
        double price = getPrice(recipe.getItem1().copy());

        if (!hasMoney(player, price)) {
            sendPlayerMessage(player, config.getString("messages.noMoney", "You don't have enough money!"));
            return false;
        }

        if (!scheduleSecondIngredientCleanup(clickEvent, recipe, 1)) {
            return false;
        }

        EconomyManager.takeMoney(player.getName(), price);

        if (!(shopkeeper instanceof AdminShopkeeper)) {
            depositToShopOwner(shopkeeper, price);
        }
        sendPlayerMessage(player, config.getString("messages.buySuccess", BUY_SUCCESS_FALLBACK)
                .replace("%item%", getItemDisplayNameSafe(recipe.getResultItem()))
                .replace("%price%", formatPrice(price)));
        return true;
    }

    private void updateTradeSlotsPostTrade(Player player, TradingRecipe recipe, Shopkeeper shopkeeper) {
         // Logic to refill slot 0 with money item if affordable
         Inventory inv = player.getOpenInventory().getTopInventory();
         if (inv instanceof MerchantInventory) {
             ItemStack firstIngredient = recipe.getItem1().copy();
             
             // Case 1: Buying (Input is Money)
             if (isEconomyItem(firstIngredient)) {
                 double price = getPrice(firstIngredient);
                 
                 double balance = EconomyManager.getBalance(player.getName());
                 
                 if (balance < price) {
                     inv.setItem(0, null); // Clear slot if cannot afford even one
                     return;
                 }

                 // Determine max affordable stack locally
                 int maxStack = 64;
                 
                 // Calculate how many we can afford based on cached balance
                 int affordable = (int) (balance / price);
                 maxStack = Math.min(maxStack, affordable);

                 if (maxStack > 0) {
                     ItemStack toAdd = firstIngredient.clone();
                     toAdd.setAmount(maxStack);
                     inv.setItem(0, toAdd);
                     // Also remove from player inventory visual?
                     Bukkit.getScheduler().runTaskLater(plugin, () -> me.w41k3r.shopkeepersAddon.gui.managers.Utils.removeEconomyItem(player), 1L);
                 } else {
                     inv.setItem(0, null);
                 }
             }
             // Case 2: Selling (Result is Money) - Check if Owner can afford to buy more
             else if (isEconomyItem(recipe.getResultItem().copy())) {
                  if (shopkeeper instanceof PlayerShopkeeper) {
                      double price = getPrice(recipe.getResultItem().copy());
                      double ownerMoney = getOwnerMoney(shopkeeper);
                      
                      if (ownerMoney < price) {
                          inv.setItem(0, null); // Clear input slot to prevent further sales
                      }
                  }
             }
         }
    }

    private void processBulkTrade(Player player, Shopkeeper shopkeeper, TradingRecipe recipe,
            InventoryClickEvent event, int requestedTradeCount) {
        int tradeCount = limitTradeCountBySecondIngredient(event, recipe, requestedTradeCount);
        if (tradeCount <= 0) {
            return;
        }

        double pricePerTrade = getPrice(recipe.getItem1().copy());
        double totalPrice = pricePerTrade * tradeCount;

        if (!hasMoney(player, totalPrice)) {
            sendPlayerMessage(player, config.getString("messages.noMoney", "You don't have enough money!"));
            return;
        }

        ItemStack resultItem = recipe.getResultItem().copy();
        resultItem.setAmount(tradeCount * resultItem.getAmount());

        if (!canFitItem(player.getInventory(), resultItem)) {
            sendPlayerMessage(player, config.getString("messages.inventoryFull", INVENTORY_FULL_FALLBACK));
            return;
        }

        if (!consumeSecondIngredientNow(event, recipe, tradeCount)) {
            return;
        }

        EconomyManager.takeMoney(player.getName(), totalPrice);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(resultItem);
        dropLeftovers(player, leftovers);
        if (!(shopkeeper instanceof AdminShopkeeper)) {
            PlayerShopkeeper playerShopkeeper = (PlayerShopkeeper) shopkeeper;
            if (playerShopkeeper.getContainer().getState() instanceof Container cont) {
                Inventory inv = cont.getInventory();
                inv.removeItem(resultItem);
            }
            depositToShopOwner(shopkeeper, totalPrice);

        }

        sendPlayerMessage(player, config.getString("messages.buySuccess", BUY_SUCCESS_FALLBACK)
                .replace("%item%", getItemDisplayNameSafe(recipe.getResultItem()))
                .replace("%price%", formatPrice(totalPrice)));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (ShopkeepersAPI.getUIRegistry().getUISession(player) != null) {
                updateTradeSlotsPostTrade(player, recipe, shopkeeper);
            }
        }, 1L);
    }

    private boolean canFitItem(Inventory inventory, ItemStack item) {
        if (inventory == null || isEmpty(item)) {
            return false;
        }

        int remaining = item.getAmount();
        int maxStackSize = Math.min(inventory.getMaxStackSize(), item.getMaxStackSize());
        if (maxStackSize <= 0) {
            return false;
        }
        for (ItemStack content : inventory.getStorageContents()) {
            if (remaining <= 0) {
                return true;
            }
            if (isEmpty(content)) {
                remaining -= maxStackSize;
            } else if (content.isSimilar(item) && content.getAmount() < maxStackSize) {
                remaining -= maxStackSize - content.getAmount();
            }
        }
        return remaining <= 0;
    }

    private void dropLeftovers(Player player, Map<Integer, ItemStack> leftovers) {
        if (leftovers == null || leftovers.isEmpty()) {
            return;
        }
        for (ItemStack leftover : leftovers.values()) {
            if (!isEmpty(leftover)) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        debugLog("Dropped leftover bulk trade payout items for " + player.getName());
    }

    private void depositToShopOwner(Shopkeeper shopkeeper, double price) {
        String ownerName = getShopkeeperOwnerName(shopkeeper);
        if (ownerName != null) {
            EconomyManager.giveMoney(ownerName, price);
        } else {
            debugLog("Could not find owner UUID for shopkeeper: " + shopkeeper.getId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;

        if (isEconomyItemClick(event, player)) {
            handleEconomyItemClick(event);
        }
    }

    private boolean isEconomyItemClick(InventoryClickEvent event, Player player) {
        return isEconomyItem(event.getCurrentItem()) &&
                ShopkeepersAPI.getUIRegistry().getUISession(player) == null;
    }

    private void processEconomyTradeClick(InventoryClickEvent event, Player player, MerchantRecipe recipe,
            TradingRecipe tradingRecipe) {
        Shopkeeper shopkeeper = ShopkeepersAPI.getUIRegistry().getUISession(player).getShopkeeper();
        boolean isAdminShopkeeper = shopkeeper instanceof AdminShopkeeper;
        double pricePerTrade = getPrice(recipe.getResult());
        List<ItemStack> sellIngredients = getSellIngredients(event, recipe, tradingRecipe);

        int maxTrades = calculateMaxTrades(event, sellIngredients, shopkeeper, pricePerTrade);
        if (maxTrades <= 0) {
            if (isAdminShopkeeper && DailyEarningsManager.isLimitEnabled()
                    && DailyEarningsManager.getRemainingLimit(player) < pricePerTrade) {
                double remaining = DailyEarningsManager.getRemainingLimit(player);
                sendPlayerMessage(player, config.getString("messages.dailyLimitReached", DAILY_LIMIT_FALLBACK)
                        .replace("%limit%", formatPrice(DailyEarningsManager.getDailyLimit()))
                        .replace("%remaining%", formatPrice(remaining)));
                recipe.setUses(recipe.getMaxUses());
            } else if (!isAdminShopkeeper && getOwnerMoney(shopkeeper) < pricePerTrade) {
                sendNoMoneyOwner(player, recipe);
            }
            event.setCancelled(true);
            return;
        }

        double totalPrice = pricePerTrade * maxTrades;
        boolean traded;

        if (isAdminShopkeeper) {
            if (DailyEarningsManager.isLimitEnabled()) {
                double remaining = DailyEarningsManager.getRemainingLimit(player);
                if (remaining < pricePerTrade) {
                    sendPlayerMessage(player, config.getString("messages.dailyLimitReached", DAILY_LIMIT_FALLBACK)
                            .replace("%limit%", formatPrice(DailyEarningsManager.getDailyLimit()))
                            .replace("%remaining%", formatPrice(remaining)));
                    event.setCancelled(true);
                    recipe.setUses(recipe.getMaxUses());
                    return;
                }
            }
            traded = handleAdminTrade(event, sellIngredients, player, maxTrades, totalPrice);
        } else {
            traded = handlePlayerShopTrade(event, recipe, sellIngredients, player, shopkeeper, maxTrades, totalPrice);
        }

        if (!traded) {
            return;
        }

        sendPlayerMessage(player, config.getString("messages.sellSuccess", SELL_SUCCESS_FALLBACK)
                .replace("%item%", getItemDisplayNameSafe(sellIngredients.isEmpty() ? null : sellIngredients.get(0)))
                .replace("%price%", formatPrice(totalPrice)));
    }

    private TradeProcessResult processEconomyTradeFallback(Player player, Shopkeeper shopkeeper,
            MerchantInventory merchantInventory,
            MerchantRecipe merchantRecipe, TradingRecipe tradingRecipe) {
        if (player == null || shopkeeper == null || merchantInventory == null || tradingRecipe == null) {
            return TradeProcessResult.UNRECOVERABLE_FAILURE;
        }

        boolean isAdminShopkeeper = shopkeeper instanceof AdminShopkeeper;
        ItemStack resultItem = tradingRecipe.getResultItem().copy();
        if (!isEconomyItem(resultItem)) {
            return TradeProcessResult.UNRECOVERABLE_FAILURE;
        }

        double pricePerTrade = getPrice(resultItem);
        List<ItemStack> sellIngredients = getSellIngredients(merchantInventory, merchantRecipe, tradingRecipe);
        int maxTrades = Math.min(1, calculateMaxTradesByIngredients(merchantInventory, null, sellIngredients, false));

        if (isAdminShopkeeper && DailyEarningsManager.isLimitEnabled()) {
            double remaining = DailyEarningsManager.getRemainingLimit(player);
            if (remaining < pricePerTrade) {
                sendPlayerMessage(player, config.getString("messages.dailyLimitReached", DAILY_LIMIT_FALLBACK)
                        .replace("%limit%", formatPrice(DailyEarningsManager.getDailyLimit()))
                        .replace("%remaining%", formatPrice(remaining)));
                if (merchantRecipe != null) {
                    merchantRecipe.setUses(merchantRecipe.getMaxUses());
                }
                return TradeProcessResult.HANDLED_FAILURE;
            }
        } else if (!isAdminShopkeeper && getOwnerMoney(shopkeeper) < pricePerTrade) {
            sendNoMoneyOwner(player, merchantRecipe);
            return TradeProcessResult.HANDLED_FAILURE;
        }

        String ownerName = null;
        if (!isAdminShopkeeper) {
            ownerName = getShopkeeperOwnerName(shopkeeper);
            if (ownerName == null) {
                return TradeProcessResult.UNRECOVERABLE_FAILURE;
            }
        }

        if (maxTrades <= 0 || !consumeSellIngredients(merchantInventory, null, false, sellIngredients, 1)) {
            return TradeProcessResult.UNRECOVERABLE_FAILURE;
        }

        if (isAdminShopkeeper) {
            EconomyManager.giveMoney(player.getName(), pricePerTrade);
            if (DailyEarningsManager.isLimitEnabled()) {
                DailyEarningsManager.addEarnings(player, pricePerTrade);
            }
        } else {
            EconomyManager.takeMoney(ownerName, pricePerTrade);
            EconomyManager.giveMoney(player.getName(), pricePerTrade);
            depositIngredientsToContainer(shopkeeper, sellIngredients, 1);
        }

        sendPlayerMessage(player, config.getString("messages.sellSuccess", SELL_SUCCESS_FALLBACK)
                .replace("%item%", getItemDisplayNameSafe(sellIngredients.isEmpty() ? null : sellIngredients.get(0)))
                .replace("%price%", formatPrice(pricePerTrade)));
        return TradeProcessResult.SUCCESS;
    }

    private int calculateMaxTrades(InventoryClickEvent event, List<ItemStack> ingredients, Shopkeeper shopkeeper,
            double pricePerTrade) {
        int maxTrades = event.isShiftClick()
                ? calculateShiftClickTrades(event, ingredients, shopkeeper, pricePerTrade)
                : Math.min(1, calculateMaxTradesByIngredients(event, ingredients, false));

        if (shopkeeper instanceof AdminShopkeeper && DailyEarningsManager.isLimitEnabled()) {
            Player player = (Player) event.getWhoClicked();
            double remaining = DailyEarningsManager.getRemainingLimit(player);
            int affordableByLimit = (int) Math.floor(remaining / pricePerTrade);
            maxTrades = Math.min(maxTrades, affordableByLimit);
        }

        return Math.max(0, maxTrades);
    }

    private int calculateShiftClickTrades(InventoryClickEvent event, List<ItemStack> ingredients,
            Shopkeeper shopkeeper, double pricePerTrade) {
        int maxTradesByItems = calculateMaxTradesByIngredients(event, ingredients, true);

        if (shopkeeper instanceof AdminShopkeeper) {
            return maxTradesByItems;
        }

        double ownerMoney = getOwnerMoney(shopkeeper);
        int affordableTrades = (int) Math.floor(ownerMoney / pricePerTrade);

        return Math.min(maxTradesByItems, affordableTrades);
    }

    private int calculateMaxTradesByIngredients(InventoryClickEvent event, List<ItemStack> ingredients,
            boolean includePlayerInventory) {
        return calculateMaxTradesByIngredients(getMerchantInventory(event), getPlayerInventory(event), ingredients,
                includePlayerInventory);
    }

    private int calculateMaxTradesByIngredients(Inventory merchantInventory, Inventory playerInventory,
            List<ItemStack> ingredients, boolean includePlayerInventory) {
        int maxTrades = Integer.MAX_VALUE;
        MatchCache matchCache = new MatchCache();

        for (ItemStack ingredient : aggregateIngredients(ingredients, 1)) {
            int required = ingredient.getAmount();
            int available = countAvailableIngredients(merchantInventory, playerInventory, ingredient,
                    includePlayerInventory, matchCache);
            maxTrades = Math.min(maxTrades, available / required);
        }

        return maxTrades == Integer.MAX_VALUE ? 0 : maxTrades;
    }

    private List<ItemStack> getSellIngredients(InventoryClickEvent event, MerchantRecipe merchantRecipe,
            TradingRecipe tradingRecipe) {
        return getSellIngredients(getMerchantInventory(event), merchantRecipe, tradingRecipe);
    }

    private List<ItemStack> getSellIngredients(Inventory merchantInventory, MerchantRecipe merchantRecipe,
            TradingRecipe tradingRecipe) {
        List<ItemStack> ingredients = new ArrayList<>();

        if (tradingRecipe != null) {
            addSellIngredient(ingredients, tradingRecipe.getItem1().copy());
            if (tradingRecipe.hasItem2()) {
                addSellIngredient(ingredients, tradingRecipe.getItem2().copy());
            }
            if (!ingredients.isEmpty()) {
                return ingredients;
            }
        }

        if (merchantRecipe != null && merchantRecipe.getIngredients() != null) {
            for (ItemStack ingredient : merchantRecipe.getIngredients()) {
                addSellIngredient(ingredients, ingredient);
            }
            if (!ingredients.isEmpty()) {
                return ingredients;
            }
            for (ItemStack ingredient : merchantRecipe.getIngredients()) {
                if (!isEmpty(ingredient)) {
                    ingredients.add(ingredient.clone());
                }
            }
            if (!ingredients.isEmpty()) {
                return ingredients;
            }
        }

        if (merchantInventory instanceof MerchantInventory) {
            for (int slot = 0; slot <= 1; slot++) {
                addSellIngredient(ingredients, merchantInventory.getItem(slot));
            }
        }

        return ingredients;
    }

    private void addSellIngredient(List<ItemStack> ingredients, ItemStack item) {
        if (!isEmpty(item) && !isEconomyItem(item)) {
            ingredients.add(item.clone());
        }
    }

    private int getInputTradeCount(InventoryClickEvent event) {
        Inventory merchantInventory = getMerchantInventory(event);
        if (merchantInventory instanceof MerchantInventory) {
            ItemStack input = merchantInventory.getItem(0);
            if (!isEmpty(input)) {
                return input.getAmount();
            }
        }
        return 1;
    }

    private boolean consumeSellIngredients(InventoryClickEvent event, List<ItemStack> ingredients, int tradeCount) {
        if (event == null || tradeCount <= 0) {
            return false;
        }
        return consumeSellIngredients(getMerchantInventory(event), getPlayerInventory(event), event.isShiftClick(),
                ingredients, tradeCount);
    }

    private boolean consumeSellIngredients(Inventory merchantInventory, Inventory playerInventory,
            boolean includePlayerInventory, List<ItemStack> ingredients, int tradeCount) {
        if (tradeCount <= 0) {
            return false;
        }
        MatchCache matchCache = new MatchCache();
        if (ingredients == null || ingredients.isEmpty()) {
            return false;
        }

        List<ItemStack> requiredIngredients = aggregateIngredients(ingredients, tradeCount);
        for (ItemStack ingredient : requiredIngredients) {
            int required = ingredient.getAmount();
            int available = countAvailableIngredients(merchantInventory, playerInventory, ingredient,
                    includePlayerInventory, matchCache);
            if (available < required) {
                return false;
            }
        }

        boolean consumedAny = false;
        List<ItemStack> cleanupTargets = new ArrayList<>();
        List<Integer> expectedRemaining = new ArrayList<>();
        for (ItemStack ingredient : requiredIngredients) {
            int required = ingredient.getAmount();
            int removedFromMerchant = removeMatchingInput(merchantInventory, ingredient, required, matchCache);
            int remaining = required - removedFromMerchant;
            int removedFromPlayer = includePlayerInventory && remaining > 0
                    ? removeMatching(playerInventory, ingredient, remaining, matchCache)
                    : 0;
            if (removedFromPlayer + removedFromMerchant != required) {
                return false;
            }
            consumedAny = true;
            cleanupTargets.add(ingredient.clone());
            expectedRemaining.add(countMatchingInput(merchantInventory, ingredient, matchCache));
        }
        scheduleIngredientCleanup(merchantInventory, cleanupTargets, expectedRemaining);
        return consumedAny;
    }

    private List<ItemStack> aggregateIngredients(List<ItemStack> ingredients, int multiplier) {
        List<ItemStack> aggregated = new ArrayList<>();
        MatchCache matchCache = new MatchCache();
        if (ingredients == null || multiplier <= 0) {
            return aggregated;
        }

        for (ItemStack ingredient : ingredients) {
            if (isEmpty(ingredient)) {
                continue;
            }

            ItemStack required = ingredient.clone();
            required.setAmount(ingredient.getAmount() * multiplier);
            ItemStack existing = findMatchingIngredient(aggregated, required, matchCache);
            if (existing == null) {
                aggregated.add(required);
            } else {
                existing.setAmount(existing.getAmount() + required.getAmount());
            }
        }
        return aggregated;
    }

    private ItemStack findMatchingIngredient(List<ItemStack> ingredients, ItemStack target, MatchCache matchCache) {
        for (ItemStack ingredient : ingredients) {
            if (matches(ingredient, target, matchCache) && matches(target, ingredient, matchCache)) {
                return ingredient;
            }
        }
        return null;
    }

    private boolean scheduleSecondIngredientCleanup(InventoryClickEvent event, TradingRecipe recipe, int tradeCount) {
        return scheduleSecondIngredientCleanup(getMerchantInventory(event), recipe, tradeCount);
    }

    private boolean scheduleSecondIngredientCleanup(Inventory merchantInventory, TradingRecipe recipe, int tradeCount) {
        ItemStack ingredient = getSecondIngredient(recipe);
        if (ingredient == null) {
            return true;
        }
        MatchCache matchCache = new MatchCache();
        int required = ingredient.getAmount() * tradeCount;
        int before = countMatchingInput(merchantInventory, ingredient, matchCache);
        if (before < required) {
            return false;
        }

        List<ItemStack> cleanupTargets = new ArrayList<>();
        List<Integer> expectedRemaining = new ArrayList<>();
        cleanupTargets.add(ingredient.clone());
        expectedRemaining.add(before - required);
        scheduleIngredientCleanup(merchantInventory, cleanupTargets, expectedRemaining);
        return true;
    }

    private int limitTradeCountBySecondIngredient(InventoryClickEvent event, TradingRecipe recipe, int requestedTradeCount) {
        ItemStack ingredient = getSecondIngredient(recipe);
        if (ingredient == null) {
            return requestedTradeCount;
        }
        MatchCache matchCache = new MatchCache();
        int requiredPerTrade = ingredient.getAmount();
        int available = countMatching(getPlayerInventory(event), ingredient, matchCache)
                + countMatchingInput(getMerchantInventory(event), ingredient, matchCache);
        return Math.min(requestedTradeCount, available / requiredPerTrade);
    }

    private boolean consumeSecondIngredientNow(InventoryClickEvent event, TradingRecipe recipe, int tradeCount) {
        ItemStack ingredient = getSecondIngredient(recipe);
        if (ingredient == null) {
            return true;
        }
        int required = ingredient.getAmount() * tradeCount;
        Inventory playerInventory = getPlayerInventory(event);
        Inventory merchantInventory = getMerchantInventory(event);
        MatchCache matchCache = new MatchCache();
        if (countMatching(playerInventory, ingredient, matchCache)
                + countMatchingInput(merchantInventory, ingredient, matchCache) < required) {
            return false;
        }
        int removedFromPlayer = removeMatching(playerInventory, ingredient, required, matchCache);
        int remaining = required - removedFromPlayer;
        if (remaining > 0) {
            removeMatchingInput(merchantInventory, ingredient, remaining, matchCache);
        }
        return true;
    }

    private ItemStack getSecondIngredient(TradingRecipe recipe) {
        if (recipe == null || !recipe.hasItem2()) {
            return null;
        }
        ItemStack item = recipe.getItem2().copy();
        return isEmpty(item) ? null : item;
    }

    private Inventory getPlayerInventory(InventoryClickEvent event) {
        return event == null ? null : event.getWhoClicked().getInventory();
    }

    private MerchantInventory getOpenMerchantInventory(Player player) {
        if (player == null || player.getOpenInventory() == null) {
            return null;
        }
        Inventory topInventory = player.getOpenInventory().getTopInventory();
        return topInventory instanceof MerchantInventory merchantInventory ? merchantInventory : null;
    }

    private Inventory getMerchantInventory(InventoryClickEvent event) {
        if (event == null || event.getView() == null) {
            return null;
        }
        Inventory topInventory = event.getView().getTopInventory();
        return topInventory instanceof MerchantInventory ? topInventory : null;
    }

    private int countMatching(Inventory inventory, ItemStack target) {
        return countMatching(inventory, target, new MatchCache());
    }

    private int countMatching(Inventory inventory, ItemStack target, MatchCache matchCache) {
        if (inventory == null || isEmpty(target)) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (matches(item, target, matchCache)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countMatchingInput(Inventory inventory, ItemStack target) {
        return countMatchingInput(inventory, target, new MatchCache());
    }

    private int countMatchingInput(Inventory inventory, ItemStack target, MatchCache matchCache) {
        if (!(inventory instanceof MerchantInventory merchantInventory) || isEmpty(target)) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot <= 1; slot++) {
            ItemStack item = merchantInventory.getItem(slot);
            if (matches(item, target, matchCache)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countAvailableIngredients(InventoryClickEvent event, ItemStack target,
            boolean includePlayerInventory, MatchCache matchCache) {
        return countAvailableIngredients(getMerchantInventory(event), getPlayerInventory(event), target,
                includePlayerInventory, matchCache);
    }

    private int countAvailableIngredients(Inventory merchantInventory, Inventory playerInventory, ItemStack target,
            boolean includePlayerInventory, MatchCache matchCache) {
        int available = countMatchingInput(merchantInventory, target, matchCache);
        if (includePlayerInventory) {
            available += countMatching(playerInventory, target, matchCache);
        }
        return available;
    }

    private void scheduleIngredientCleanup(Inventory merchantInventory, List<ItemStack> targets,
            List<Integer> expectedRemaining) {
        if (merchantInventory == null || targets == null || expectedRemaining == null) {
            debugLog("Skipped ingredient cleanup: merchant inventory, targets, or expected remaining list was null");
            return;
        }
        if (targets.size() != expectedRemaining.size()) {
            debugLog("Skipped ingredient cleanup: targets and expected remaining list sizes differed (targets="
                    + targets.size() + ", expected=" + expectedRemaining.size() + ")");
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            MatchCache matchCache = new MatchCache();
            for (int index = 0; index < targets.size(); index++) {
                ItemStack target = targets.get(index);
                int expected = expectedRemaining.get(index);
                int current = countMatchingInput(merchantInventory, target, matchCache);
                if (current > expected) {
                    removeMatchingInput(merchantInventory, target, current - expected, matchCache);
                }
            }
            if (merchantInventory instanceof MerchantInventory merchant
                    && merchant.getViewers().stream().findFirst().orElse(null) instanceof Player player) {
                player.updateInventory();
            }
        }, 1L);
    }

    private int removeMatching(Inventory inventory, ItemStack target, int amount) {
        return removeMatching(inventory, target, amount, new MatchCache());
    }

    private int removeMatching(Inventory inventory, ItemStack target, int amount, MatchCache matchCache) {
        if (inventory == null || amount <= 0) {
            return 0;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!matches(item, target, matchCache)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            int newAmount = item.getAmount() - take;
            if (newAmount <= 0) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(newAmount);
                inventory.setItem(slot, item);
            }
            remaining -= take;
        }
        return amount - remaining;
    }

    private int removeMatchingInput(Inventory inventory, ItemStack target, int amount) {
        return removeMatchingInput(inventory, target, amount, new MatchCache());
    }

    private int removeMatchingInput(Inventory inventory, ItemStack target, int amount, MatchCache matchCache) {
        if (!(inventory instanceof MerchantInventory merchantInventory) || amount <= 0) {
            return 0;
        }
        int remaining = amount;
        for (int slot = 0; slot <= 1 && remaining > 0; slot++) {
            ItemStack item = merchantInventory.getItem(slot);
            if (!matches(item, target, matchCache)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            int newAmount = item.getAmount() - take;
            if (newAmount <= 0) {
                merchantInventory.setItem(slot, null);
            } else {
                item.setAmount(newAmount);
                merchantInventory.setItem(slot, item);
            }
            remaining -= take;
        }
        return amount - remaining;
    }

    private boolean matches(ItemStack item, ItemStack target) {
        return matches(item, target, new MatchCache());
    }

    private boolean matches(ItemStack item, ItemStack target, MatchCache matchCache) {
        if (isEmpty(item) || isEmpty(target) || item.getType() != target.getType()) {
            return false;
        }
        if (item.isSimilar(target)) {
            return true;
        }
        MatchProfile itemProfile = matchCache.get(item);
        MatchProfile targetProfile = matchCache.get(target);
        if (targetProfile.craftEngineId != null
                && targetProfile.craftEngineId.equals(itemProfile.craftEngineId)) {
            return true;
        }
        if (targetProfile.itemModel != null && targetProfile.itemModel.equals(itemProfile.itemModel)) {
            return targetProfile.customModelData == null || sameCustomModelData(itemProfile, targetProfile);
        }
        return targetProfile.customModelData != null && sameCustomModelData(itemProfile, targetProfile);
    }

    private boolean sameCustomModelData(MatchProfile item, MatchProfile target) {
        return Objects.equals(target.customModelData, item.customModelData);
    }

    private MatchProfile createMatchProfile(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        String metaString = getMetaString(item);
        return new MatchProfile(
                getCraftEngineIdValue(meta, metaString),
                getItemModelValue(meta, metaString),
                getCustomModelDataValue(item, metaString));
    }

    private final class MatchCache {
        private final Map<ItemStack, MatchProfile> profiles = new IdentityHashMap<>();

        private MatchProfile get(ItemStack item) {
            return profiles.computeIfAbsent(item, EconomyListener.this::createMatchProfile);
        }
    }

    private static final class MatchProfile {
        private final String craftEngineId;
        private final String itemModel;
        private final String customModelData;

        private MatchProfile(String craftEngineId, String itemModel, String customModelData) {
            this.craftEngineId = craftEngineId;
            this.itemModel = itemModel;
            this.customModelData = customModelData;
        }
    }

    private String getCraftEngineIdValue(ItemMeta meta, String metaString) {
        String value = getPersistentStringValue(meta, "craftengine:id");
        if (value != null) {
            return normalizeIdentifier(value);
        }
        value = getPersistentStringValue(meta, "craftengine:item_id");
        if (value != null) {
            return normalizeIdentifier(value);
        }
        return normalizeIdentifier(getMetaValue(metaString, "craftengine:id"));
    }

    private String getItemModelValue(ItemMeta meta, String metaString) {
        Object itemModel = invokeMetaMethod(meta, "getItemModel");
        if (itemModel != null) {
            return normalizeIdentifier(itemModel.toString());
        }
        String value = getPersistentStringValue(meta, "minecraft:item_model");
        if (value != null) {
            return normalizeIdentifier(value);
        }
        return normalizeIdentifier(getFirstMetaValue(metaString, "minecraft:item_model", "item_model"));
    }

    private String getPersistentStringValue(ItemMeta meta, String key) {
        if (meta == null || key == null) {
            return null;
        }
        try {
            NamespacedKey namespacedKey = NamespacedKey.fromString(key);
            if (namespacedKey == null) {
                return null;
            }
            String value = meta.getPersistentDataContainer().get(namespacedKey, PersistentDataType.STRING);
            return value == null || value.isBlank() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object invokeMetaMethod(ItemMeta meta, String methodName) {
        if (meta == null) {
            return null;
        }
        try {
            Method method = meta.getClass().getMethod(methodName);
            return method.invoke(meta);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private String getCustomModelDataValue(ItemStack item, String metaString) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        try {
            if (meta.hasCustomModelData()) {
                return normalizeCustomModelData(String.valueOf(meta.getCustomModelData()));
            }
        } catch (Throwable ignored) {
        }
        if (metaString == null) {
            return null;
        }
        String value = extractCustomModelDataFloat(metaString);
        if (value != null) {
            return normalizeCustomModelData(value);
        }
        value = getMetaValue(metaString, "minecraft:custom_model_data");
        if (value == null) {
            value = getMetaValue(metaString, "custom-model-data");
        }
        if (value == null) {
            value = getMetaValue(metaString, "custom_model_data");
        }
        return normalizeCustomModelData(value);
    }

    private String extractCustomModelDataFloat(String meta) {
        String[] markers = { "floats=[", "floats:" };
        for (String marker : markers) {
            int index = meta.indexOf(marker);
            if (index < 0) {
                continue;
            }
            int start = index + marker.length();
            int end = start;
            while (end < meta.length()) {
                char ch = meta.charAt(end);
                if (ch == ',' || ch == ']' || ch == '}') {
                    break;
                }
                end++;
            }
            if (end > start) {
                return meta.substring(start, end);
            }
        }
        return null;
    }

    private String normalizeCustomModelData(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        while (normalized.startsWith("[") || normalized.startsWith("{")) {
            normalized = normalized.substring(1).trim();
        }
        while (normalized.endsWith("f") || normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.endsWith(".0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private String getMetaValue(String meta, String key) {
        return meta == null ? null : extractMetaValue(meta, key);
    }

    private String getFirstMetaValue(String meta, String... keys) {
        if (meta == null) {
            return null;
        }
        for (String key : keys) {
            String value = getMetaValue(meta, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String getMetaString(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        try {
            Optional<Method> method = META_GET_AS_STRING_METHODS.computeIfAbsent(meta.getClass(),
                    EconomyListener::findGetAsStringMethod);
            if (method.isPresent()) {
                Object value = method.get().invoke(meta);
                return value == null ? null : value.toString();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return meta.toString();
    }

    private static Optional<Method> findGetAsStringMethod(Class<?> metaClass) {
        try {
            return Optional.of(metaClass.getMethod("getAsString"));
        } catch (NoSuchMethodException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    private String extractMetaValue(String meta, String key) {
        int searchFrom = 0;
        while (searchFrom < meta.length()) {
            int keyIndex = meta.indexOf(key, searchFrom);
            if (keyIndex < 0) {
                return null;
            }
            int valueStart = getMetaValueSeparator(meta, keyIndex, key);
            if (valueStart >= 0) {
                return extractMetaValueAt(meta, valueStart + 1);
            }
            searchFrom = keyIndex + key.length();
        }
        return null;
    }

    private int getMetaValueSeparator(String meta, int keyIndex, String key) {
        if (!isMetaKeyBoundary(meta, keyIndex - 1)) {
            return -1;
        }
        int index = keyIndex + key.length();
        while (index < meta.length() && Character.isWhitespace(meta.charAt(index))) {
            index++;
        }
        if (index >= meta.length()) {
            return -1;
        }
        char separator = meta.charAt(index);
        return separator == ':' || separator == '=' ? index : -1;
    }

    private boolean isMetaKeyBoundary(String meta, int index) {
        if (index < 0) {
            return true;
        }
        char ch = meta.charAt(index);
        return !Character.isLetterOrDigit(ch) && ch != '_' && ch != '-' && ch != ':';
    }

    private String extractMetaValueAt(String meta, int valueStart) {
        while (valueStart < meta.length()) {
            char ch = meta.charAt(valueStart);
            if (ch != ' ' && ch != '\"' && ch != '\\' && ch != '\'' && ch != '{' && ch != '=') {
                break;
            }
            valueStart++;
        }
        int valueEnd = valueStart;
        while (valueEnd < meta.length()) {
            char ch = meta.charAt(valueEnd);
            if (ch == '\"' || ch == '\\' || ch == '\'' || ch == ',' || ch == '}' || ch == ']' || ch == ' ') {
                break;
            }
            valueEnd++;
        }
        return valueEnd <= valueStart ? null : meta.substring(valueStart, valueEnd);
    }

    private double getOwnerMoney(Shopkeeper shopkeeper) {
        String ownerName = getShopkeeperOwnerName(shopkeeper);
        if (ownerName == null) return 0;
        return EconomyManager.getBalance(ownerName);
    }

    private void sendNoMoneyOwner(Player player, MerchantRecipe recipe) {
        if (recipe != null) {
            recipe.setUses(recipe.getMaxUses());
        }
        sendPlayerMessage(player, config.getString("messages.noMoneyOwner", NO_MONEY_OWNER_FALLBACK));
    }

    private boolean handleAdminTrade(InventoryClickEvent event, List<ItemStack> sellIngredients, Player player,
            int maxTrades, double totalPrice) {
        event.setCancelled(true);
        if (!consumeSellIngredients(event, sellIngredients, maxTrades)) {
            return false;
        }

        EconomyManager.giveMoney(player.getName(), totalPrice);
        if (DailyEarningsManager.isLimitEnabled()) {
            DailyEarningsManager.addEarnings(player, totalPrice);
        }
        return true;
    }

    private boolean handlePlayerShopTrade(InventoryClickEvent event, MerchantRecipe recipe,
            List<ItemStack> sellIngredients, Player player, Shopkeeper shopkeeper, int maxTrades, double totalPrice) {
        event.setCancelled(true);

        double ownerMoney = getOwnerMoney(shopkeeper);
        if (ownerMoney < totalPrice) {
            sendNoMoneyOwner(player, recipe);
            return false;
        }

        String ownerName = getShopkeeperOwnerName(shopkeeper);
        if (ownerName == null) {
            sendPlayerMessage(player, config.getString("messages.error", ERROR_FALLBACK));
            return false;
        }

        if (!consumeSellIngredients(event, sellIngredients, maxTrades)) {
            return false;
        }

        EconomyManager.takeMoney(ownerName, totalPrice);
        EconomyManager.giveMoney(player.getName(), totalPrice);
        depositIngredientsToContainer(shopkeeper, sellIngredients, maxTrades);
        return true;
    }

    private String getShopkeeperOwnerUUID(Shopkeeper shopkeeper) {
        if (shopkeeper == null) {
            return null;
        }
        String shopkeeperId = String.valueOf(shopkeeper.getId());
        try {
            refreshOwnerUuidCache();
            return ownerUuidByShopkeeperId.get(shopkeeperId);
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to get owner UUID for shopkeeper: " + shopkeeper.getId(), e);
            return null;
        }
    }

    private String getShopkeeperOwnerName(Shopkeeper shopkeeper) {
        String ownerUUID = getShopkeeperOwnerUUID(shopkeeper);
        if (ownerUUID == null) {
            return null;
        }
        try {
            String ownerName = Bukkit.getOfflinePlayer(java.util.UUID.fromString(ownerUUID)).getName();
            if (ownerName == null) {
                debugLog("Could not determine owner name for UUID: " + ownerUUID);
            }
            return ownerName;
        } catch (IllegalArgumentException e) {
            debugLog("Invalid owner UUID for shopkeeper " + shopkeeper.getId() + ": " + ownerUUID);
            return null;
        }
    }

    private void refreshOwnerUuidCache() {
        long now = System.currentTimeMillis();
        if (now < nextOwnerCacheRefreshMillis) {
            return;
        }
        nextOwnerCacheRefreshMillis = now + OWNER_CACHE_REFRESH_INTERVAL_MILLIS;

        File dataFile = new File(plugin.getDataFolder().getParentFile(), SHOPKEEPERS_DATA_PATH);
        long lastModified = dataFile.lastModified();
        if (lastModified == ownerCacheLastModified) {
            return;
        }

        YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        Map<String, String> refreshedOwnerUuids = new ConcurrentHashMap<>();
        for (String shopkeeperId : dataConfig.getKeys(false)) {
            String ownerUUID = dataConfig.getString(shopkeeperId + OWNER_UUID_PATH);
            if (ownerUUID != null) {
                refreshedOwnerUuids.put(shopkeeperId, ownerUUID);
            }
        }
        ownerUuidByShopkeeperId.clear();
        ownerUuidByShopkeeperId.putAll(refreshedOwnerUuids);
        ownerCacheLastModified = lastModified;
    }

    /**
     * Helper to get display name from item or fall back to type name.
     * Uses Object to handle both ItemStack and UnmodifiableItemStack.
     */
    private String getItemDisplayNameSafe(Object itemObj) {
        if (!(itemObj instanceof ItemStack item)) {
            return "UNKNOWN";
        }
        if (item.getItemMeta() == null) {
            return item.getType().name();
        }
        String displayName = item.getItemMeta().getDisplayName();
        return displayName.isEmpty() ? item.getType().name() : displayName;
    }

    private void depositIngredientsToContainer(Shopkeeper shopkeeper, List<ItemStack> ingredients, int multiplier) {
        if (!(shopkeeper instanceof PlayerShopkeeper playerShopkeeper))
            return;

        try {
            Inventory container = ((Chest) playerShopkeeper.getContainer().getState()).getBlockInventory();

            for (ItemStack ingredient : ingredients) {
                if (ingredient == null || ingredient.getType() == Material.AIR)
                    continue;

                ItemStack toAdd = ingredient.clone();
                toAdd.setAmount(ingredient.getAmount() * multiplier);
                container.addItem(toAdd);
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to deposit ingredients to shop container", e);
        }
    }

    private void handleEconomyItemClick(InventoryClickEvent event) {
        event.setCancelled(true);
        removeEconomyItemsFromInventory(event.getInventory());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleRemoveEconomyItem(player);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player))
            return;

        if (ShopkeepersAPI.getUIRegistry().getUISession(player) == null) {
            removeEconomyItemsFromInventory(event.getInventory());
        }
        scheduleRemoveEconomyItem(player);
    }

    @EventHandler
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (!isEconomyItem(event.getItem().getItemStack()))
            return;

        if (event.getInventory().getHolder() instanceof Player player) {
            scheduleRemoveEconomyItem(player);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!isEconomyItem(event.getItemDrop().getItemStack()))
            return;

        event.setCancelled(true);
        scheduleRemoveEconomyItem(event.getPlayer());
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        if (isEconomyItem(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void scheduleRemoveEconomyItem(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeEconomyItem(player), REMOVE_ECONOMY_ITEM_DELAY);
    }

    private void removeEconomyItemsFromInventory(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isEconomyItem(item)) {
                inventory.remove(item);
                break; // Remove one at a time, will be called again if needed
            }
        }
    }
}
