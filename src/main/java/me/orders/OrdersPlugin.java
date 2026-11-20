package me.orders;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

public class OrdersPlugin extends JavaPlugin implements Listener {

    // ---------- types ----------
    enum Type { BROWSE, MINE, DELIVER, PICK, REVIEW }
    enum Stage { AMOUNT, PRICE }

    enum Sort {
        RECENT("Recently Listed", Comparator.comparingLong((Order o) -> -o.created)),
        MONEY("Most Money", Comparator.comparingDouble((Order o) -> -(o.price * o.remaining()))),
        PRICE("Highest Price", Comparator.comparingDouble((Order o) -> -o.price));

        final String label;
        final Comparator<Order> cmp;

        Sort(String label, Comparator<Order> cmp) {
            this.label = label;
            this.cmp = cmp;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    static class Menu implements InventoryHolder {
        final Type type;
        final Order order;
        int page;
        Inventory inv;

        Menu(Type type, Order order) {
            this.type = type;
            this.order = order;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    static class Draft {
        ItemStack item;
        int amount = 0;
        double price = 0;
    }

    // ---------- state ----------
    private Economy eco;
    private File file;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, Stage> awaiting = new HashMap<>();
    private final Map<UUID, Sort> sorts = new HashMap<>();
    private final DecimalFormat money = new DecimalFormat("#,##0.##");

    // ---------- lifecycle ----------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy found! Install Vault + an economy plugin (EssentialsX etc).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        eco = rsp.getProvider();
        file = new File(getDataFolder(), "orders.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::expireTick, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu
                    || awaiting.containsKey(p.getUniqueId())) p.closeInventory();
        }
        if (eco != null) save();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("orders.admin")) {
                send(p, "no-permission");
                return true;
            }
            reloadConfig();
            send(p, "reloaded");
            return true;
        }
        openBrowse(p, 0);
        return true;
    }

    // ---------- storage ----------
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("orders");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            String b = "orders." + k + ".";
            ItemStack item = y.getItemStack(b + "item");
            if (item == null) continue;
            UUID id = UUID.fromString(k);
            orders.put(id, new Order(id, UUID.fromString(y.getString(b + "owner")), y.getString(b + "name", "?"),
                    item, y.getInt(b + "amount"), y.getDouble(b + "price"), y.getInt(b + "delivered"),
                    y.getInt(b + "collected"), y.getLong(b + "created"), y.getLong(b + "expires")));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Order o : orders.values()) {
            String b = "orders." + o.id + ".";
            y.set(b + "owner", o.owner.toString());
            y.set(b + "name", o.ownerName);
            y.set(b + "item", o.item);
            y.set(b + "amount", o.amount);
            y.set(b + "price", o.price);
            y.set(b + "delivered", o.delivered);
            y.set(b + "collected", o.collected);
            y.set(b + "created", o.created);
            y.set(b + "expires", o.expires);
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            getLogger().severe("Could not save orders: " + ex.getMessage());
        }
    }

    private void expireTick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Order> it = orders.values().iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (o.remaining() > 0 && now > o.expires) {
                eco.depositPlayer(Bukkit.getOfflinePlayer(o.owner), o.remaining() * o.price);
                o.amount = o.delivered;
                changed = true;
                Player op = Bukkit.getPlayer(o.owner);
                if (op != null) send(op, "order-expired", "item", name(o.item));
            }
            if (o.remaining() == 0 && o.ready() <= 0) {
                it.remove();
                changed = true;
            }
        }
        if (changed) save();
    }

    // ---------- helpers ----------
    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    /** Translate a message key from config.yml (messages.<key>) and replace {placeholders}. */
    private String tr(String key, String... kv) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    private void send(Player p, String key, String... kv) {
        p.sendMessage(c(getConfig().getString("prefix", "&8[&bOrders&8] &7") + tr(key, kv)));
    }

    private String name(ItemStack i) {
        ItemMeta m = i.getItemMeta();
        if (m != null && m.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(m.displayName());
        }
        StringBuilder sb = new StringBuilder();
        for (String w : i.getType().name().toLowerCase().split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.displayName(c(name));
        if (lore.length > 0) im.lore(Arrays.stream(lore).map(this::c).collect(Collectors.toList()));
        i.setItemMeta(im);
        return i;
    }

    private String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(this, r);
    }

    private Menu make(Type t, Order o, int size, String title) {
        Menu m = new Menu(t, o);
        m.inv = Bukkit.createInventory(m, size, Component.text(title));
        return m;
    }

    private void fillRow(Inventory inv, int from) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = from; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private ItemStack display(Order o, boolean mine) {
        ItemStack it = o.item.clone();
        it.setAmount(Math.max(1, Math.min(it.getMaxStackSize(), o.remaining() == 0 ? 1 : o.remaining())));
        ItemMeta im = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(c(" "));
        lore.add(c("&7Owner: &f" + o.ownerName));
        lore.add(c("&7Requested: &f" + o.amount));
        lore.add(c("&7Price: &a$" + money.format(o.price) + " &7each"));
        lore.add(c("&7Delivered: &f" + o.delivered + "/" + o.amount));
        lore.add(c("&7Expires in: &f" + (o.remaining() == 0 ? "-" : time(o.expires - System.currentTimeMillis()))));
        lore.add(c(" "));
        if (mine) {
            lore.add(c("&7Ready to collect: &e" + o.ready()));
            lore.add(c("&aLeft click &7to collect items"));
            lore.add(c("&cRight click &7to cancel order"));
        } else {
            lore.add(c("&eClick to deliver items"));
        }
        im.lore(lore);
        it.setItemMeta(im);
        return it;
    }

    private int maxOrders(Player p) {
        int max = getConfig().getInt("default-max-orders", 3);
        for (PermissionAttachmentInfo pa : p.getEffectivePermissions()) {
            String n = pa.getPermission();
            if (pa.getValue() && n.startsWith("orders.max.")) {
                try {
                    max = Math.max(max, Integer.parseInt(n.substring(11)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private List<Order> mineList(Player p) {
        return orders.values().stream().filter(o -> o.owner.equals(p.getUniqueId()))
                .sorted(Comparator.comparingLong(o -> o.created)).collect(Collectors.toList());
    }

    private List<Order> browseList(Player p) {
        Sort s = sorts.getOrDefault(p.getUniqueId(), Sort.RECENT);
        return orders.values().stream().filter(o -> o.remaining() > 0).sorted(s.cmp).collect(Collectors.toList());
    }

    private void give(Player p, ItemStack template, int n) {
        while (n > 0) {
            int s = Math.min(n, template.getMaxStackSize());
            ItemStack st = template.clone();
            st.setAmount(s);
            p.getInventory().addItem(st).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            n -= s;
        }
    }

    private double parseMoney(String s) {
        try {
            s = s.toLowerCase().replace(",", "").replace("$", "").trim();
            double mult = 1;
            if (s.endsWith("k")) mult = 1e3;
            else if (s.endsWith("m")) mult = 1e6;
            else if (s.endsWith("b")) mult = 1e9;
            if (mult != 1) s = s.substring(0, s.length() - 1);
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return Math.round(v * 100.0) / 100.0;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- menus ----------
    void openBrowse(Player p, int page) {
        List<Order> list = browseList(p);
        int pages = Math.max(1, (int) Math.ceil(list.size() / 45.0));
        page = Math.max(0, Math.min(page, pages - 1));
        Menu m = make(Type.BROWSE, null, 54, "Orders (Page " + (page + 1) + ")");
        m.page = page;
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx < list.size()) m.inv.setItem(i, display(list.get(idx), false));
        }
        fillRow(m.inv, 45);
        Sort s = sorts.getOrDefault(p.getUniqueId(), Sort.RECENT);
        m.inv.setItem(45, icon(Material.ARROW, "&aPrevious Page"));
        m.inv.setItem(46, icon(Material.HOPPER, "&eSort", "&7Current: &f" + s.label, "&7Click to change"));
        m.inv.setItem(49, icon(Material.SUNFLOWER, "&eRefresh"));
        m.inv.setItem(50, icon(Material.CHEST, "&6Your Orders", "&7Click to manage your orders"));
        m.inv.setItem(53, icon(Material.ARROW, "&aNext Page"));
        p.openInventory(m.inv);
    }

    void openMine(Player p) {
        List<Order> list = mineList(p);
        Menu m = make(Type.MINE, null, 54, "Orders -> Your Orders");
        for (int i = 0; i < 45 && i < list.size(); i++) m.inv.setItem(i, display(list.get(i), true));
        fillRow(m.inv, 45);
        int max = maxOrders(p);
        if (list.size() >= max) {
            m.inv.setItem(49, icon(Material.BARRIER, "&cMax Orders", "&7You have &f" + list.size() + "/" + max,
                    "&7Buy Donut+ for more order slots"));
        } else {
            m.inv.setItem(49, icon(Material.LIME_DYE, "&aCreate Order", "&7Orders: &f" + list.size() + "/" + max));
        }
        m.inv.setItem(45, icon(Material.ARROW, "&aBack"));
        p.openInventory(m.inv);
    }

    void openDeliver(Player p, Order o) {
        Menu m = make(Type.DELIVER, o, 54, "Orders -> Deliver");
        fillRow(m.inv, 45);
        m.inv.setItem(45, icon(Material.RED_STAINED_GLASS_PANE, "&cCancel"));
        m.inv.setItem(47, display(o, false));
        m.inv.setItem(49, icon(Material.LIME_STAINED_GLASS_PANE, "&aConfirm Delivery",
                "&7Put the items in the top slots", "&7then click here."));
        p.openInventory(m.inv);
    }

    void openPick(Player p) {
        Menu m = make(Type.PICK, null, 27, "Orders -> Edit Order");
        fillRow(m.inv, 0);
        m.inv.setItem(13, icon(Material.PAPER, "&eSelect an item", "&7Click an item in your inventory"));
        m.inv.setItem(22, icon(Material.RED_STAINED_GLASS_PANE, "&cCancel"));
        p.openInventory(m.inv);
    }

    void openReview(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.item == null) return;
        Menu m = make(Type.REVIEW, null, 27, "Review Order");
        fillRow(m.inv, 0);
        ItemStack sum = d.item.clone();
        ItemMeta im = sum.getItemMeta();
        im.lore(List.of(c(" "), c("&7Item: &f" + name(d.item)), c("&7Amount: &f" + d.amount),
                c("&7Price: &a$" + money.format(d.price) + " &7each"),
                c("&7Total: &a$" + money.format(d.price * d.amount))));
        sum.setItemMeta(im);
        m.inv.setItem(4, sum);
        m.inv.setItem(10, icon(Material.RED_STAINED_GLASS_PANE, "&cCancel"));
        m.inv.setItem(12, icon(Material.ITEM_FRAME, "&eChange Item"));
        m.inv.setItem(14, icon(Material.PAPER, "&eChange Amount"));
        m.inv.setItem(16, icon(Material.GOLD_INGOT, "&eChange Price"));
        m.inv.setItem(22, icon(Material.LIME_STAINED_GLASS_PANE, "&aCreate Order"));
        p.openInventory(m.inv);
    }

    /** Opens an anvil: type the value in the rename box, click the result slot. Closing = cancel. */
    private void prompt(Player p, Stage st) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.item == null) return;
        AnvilView view = MenuType.ANVIL.create(p, Component.text(st == Stage.AMOUNT ? "How many?" : "Price per item?"));
        ItemStack left = d.item.clone();
        ItemMeta im = left.getItemMeta();
        im.displayName(Component.text("1"));
        left.setItemMeta(im);
        view.setItem(0, left);
        p.openInventory(view);
        awaiting.put(p.getUniqueId(), st); // after openInventory so the previous menu's close event is ignored
        if (st == Stage.AMOUNT) send(p, "prompt-amount", "max", String.valueOf(getConfig().getInt("max-amount", 2304)));
        else send(p, "prompt-price");
    }

    // ---------- actions ----------
    private void deliver(Player p, Menu m) {
        Order o = m.order;
        if (!orders.containsKey(o.id) || o.remaining() <= 0) {
            send(p, "order-unavailable");
            later(p::closeInventory);
            return;
        }
        int need = o.remaining(), count = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack s = m.inv.getItem(i);
            if (s == null || !s.isSimilar(o.item)) continue;
            int take = Math.min(s.getAmount(), need - count);
            if (take <= 0) break;
            count += take;
            if (take == s.getAmount()) m.inv.setItem(i, null);
            else s.setAmount(s.getAmount() - take);
        }
        if (count == 0) {
            send(p, "put-items-first");
            return;
        }
        o.delivered += count;
        double pay = count * o.price;
        eco.depositPlayer(p, pay);
        save();
        send(p, "delivered-self", "amount", String.valueOf(count), "item", name(o.item), "money", money.format(pay));
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) {
            send(owner, "delivered-owner", "player", p.getName(), "amount", String.valueOf(count), "item", name(o.item));
        }
        later(p::closeInventory);
    }

    private void collect(Player p, Order o) {
        int n = o.ready();
        if (n <= 0) {
            send(p, "nothing-to-collect");
            return;
        }
        give(p, o.item, n);
        o.collected += n;
        if (o.remaining() == 0 && o.ready() <= 0) orders.remove(o.id);
        save();
        send(p, "collected", "amount", String.valueOf(n), "item", name(o.item));
        openMine(p);
    }

    private void cancel(Player p, Order o) {
        double refund = o.remaining() * o.price;
        if (refund > 0) eco.depositPlayer(p, refund);
        o.amount = o.delivered;
        int n = o.ready();
        if (n > 0) give(p, o.item, n);
        orders.remove(o.id);
        save();
        send(p, n > 0 ? "order-cancelled-collected" : "order-cancelled",
                "refund", money.format(refund), "amount", String.valueOf(n));
        openMine(p);
    }

    private void createOrder(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.item == null || d.amount <= 0 || d.price <= 0) return;
        if (mineList(p).size() >= maxOrders(p)) {
            send(p, "max-orders");
            return;
        }
        double total = d.amount * d.price;
        if (!eco.has(p, total)) {
            send(p, "not-enough-money", "total", money.format(total));
            return;
        }
        eco.withdrawPlayer(p, total);
        long now = System.currentTimeMillis();
        long dur = getConfig().getLong("order-duration-hours", 168) * 3600_000L;
        Order o = new Order(UUID.randomUUID(), p.getUniqueId(), p.getName(), d.item.clone(), d.amount, d.price,
                0, 0, now, now + dur);
        orders.put(o.id, o);
        drafts.remove
