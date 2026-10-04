package com.haraa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HaraaPlugin extends JavaPlugin implements Listener {

    // ---------------- Settings (ticks: 20 ticks = 1 second) ----------------
    private static final int TICK = 5;                    // how often the cauldrons are processed
    private static final int STEAM_AFTER = 160;           // 8 s of heat until it "steams up"
    private static final int FERMENT_TIME = 200;          // 10 s after the mushroom is added
    private static final int DRUNK_NAUSEA_TICKS = 20 * 60 * 5;   // 5 minutes of nausea
    private static final int DRUNK_SLOW_TICKS = 20 * 60 * 3;     // 3 minutes of slowness
    private static final String TAG = "haraa_display";
    // -----------------------------------------------------------------------

    private enum Stage { YELLOW, FERMENTING, READY }

    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block b) {
            return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    private static final class Pot {
        Stage stage = Stage.YELLOW;
        int heat = 0;
        int ferment = 0;
        int age = 0;
        BlockDisplay display;
        Material shownMaterial;
        int shownLevel = -1;
    }

    private final Map<Key, Pot> pots = new HashMap<>();
    private NamespacedKey potionKey;
    private File dataFile;

    // ============================ Lifecycle ============================

    @Override
    public void onEnable() {
        potionKey = new NamespacedKey(this, "haraa");
        dataFile = new File(getDataFolder(), "cauldrons.yml");

        // Clean up leftovers (e.g. after /reload)
        for (World w : getServer().getWorlds()) {
            for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
                if (d.getScoreboardTags().contains(TAG)) d.remove();
            }
        }

        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, TICK, TICK);
        Bukkit.getScheduler().runTaskTimer(this, this::save, 20L * 60 * 5, 20L * 60 * 5);
    }

    @Override
    public void onDisable() {
        save();
        for (Pot p : pots.values()) removeDisplay(p);
        pots.clear();
    }

    // ============================ Main loop ============================

    private void tick() {
        Iterator<Map.Entry<Key, Pot>> it = pots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Pot> e = it.next();
            Key k = e.getKey();
            Pot p = e.getValue();

            World w = Bukkit.getWorld(k.world());
            if (w == null) {
                p.display = null;
                continue;
            }
            if (!w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) {
                p.display = null; // non-persistent display is discarded with the chunk
                continue;
            }

            Block b = w.getBlockAt(k.x(), k.y(), k.z());
            if (b.getType() != Material.WATER_CAULDRON) {
                removeDisplay(p);
                it.remove();
                continue;
            }

            int level = ((Levelled) b.getBlockData()).getLevel();
            boolean heated = isHeated(b);
            p.age += TICK;

            Location top = b.getLocation().add(0.5, heightFor(level) + 0.1, 0.5);

            switch (p.stage) {
                case YELLOW -> {
                    boolean wasSteaming = p.heat >= STEAM_AFTER;
                    if (heated) p.heat = Math.min(STEAM_AFTER, p.heat + TICK);
                    else p.heat = Math.max(0, p.heat - TICK);
                    boolean steaming = p.heat >= STEAM_AFTER;

                    if (heated) {
                        if (steaming) {
                            w.spawnParticle(Particle.CLOUD, top, 5, 0.15, 0.05, 0.15, 0.02);
                            if (!wasSteaming) w.playSound(top, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f);
                        } else {
                            w.spawnParticle(Particle.BUBBLE_POP, top, 3, 0.2, 0.0, 0.2, 0.0);
                            w.spawnParticle(Particle.CLOUD, top, 1, 0.15, 0.02, 0.15, 0.005);
                        }
                    }
                }
                case FERMENTING -> {
                    if (heated) {
                        p.ferment += TICK;
                        w.spawnParticle(Particle.CLOUD, top, 5, 0.15, 0.05, 0.15, 0.02);
                        w.spawnParticle(Particle.BUBBLE_POP, top, 3, 0.2, 0.0, 0.2, 0.0);
                        if (p.ferment >= FERMENT_TIME) {
                            p.stage = Stage.READY;
                            w.playSound(top, Sound.BLOCK_BREWING_STAND_BREW, 1.0f, 1.0f);
                            w.spawnParticle(Particle.CLOUD, top, 15, 0.2, 0.1, 0.2, 0.04);
                        }
                    }
                }
                case READY -> { }
            }

            if (heated && p.stage != Stage.READY && p.age % 20 == 0) {
                w.playSound(top, Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, 0.5f, 1.0f);
            }

            updateDisplay(b, p, level);
        }
    }

    private boolean isHeated(Block cauldron) {
        Block below = cauldron.getRelative(BlockFace.DOWN);
        return below.getBlockData() instanceof Campfire c && c.isLit();
    }

    // ============================ Display ============================

    private static double heightFor(int level) {
        return switch (level) {
            case 1 -> 0.5625;
            case 2 -> 0.75;
            default -> 0.9375;
        };
    }

    private Material colorFor(Pot p) {
        return p.stage == Stage.READY ? Material.WHITE_CONCRETE_POWDER : Material.YELLOW_CONCRETE_POWDER;
    }

    private void updateDisplay(Block b, Pot p, int level) {
        Material want = colorFor(p);
        Location loc = b.getLocation().add(0.125, heightFor(level) + 0.01, 0.125);

        if (p.display == null || !p.display.isValid()) {
            p.display = b.getWorld().spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(want.createBlockData());
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.addScoreboardTag(TAG);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        new AxisAngle4f(),
                        new Vector3f(0.75f, 0.01f, 0.75f),
                        new AxisAngle4f()));
            });
            p.shownMaterial = want;
            p.shownLevel = level;
            return;
        }
        if (p.shownMaterial != want) {
            p.display.setBlock(want.createBlockData());
            p.shownMaterial = want;
        }
        if (p.shownLevel != level) {
            p.display.teleport(loc);
            p.shownLevel = level;
        }
    }

    private void removeDisplay(Pot p) {
        if (p.display != null) {
            if (p.display.isValid()) p.display.remove();
            p.display = null;
        }
    }

    private void removePot(Key k) {
        Pot p = pots.remove(k);
        if (p != null) removeDisplay(p);
    }

    // ============================ Ingredients ============================

    private boolean tryPotato(Block cauldron, Player who) {
        Key k = Key.of(cauldron);
        if (pots.containsKey(k)) {
            msg(who, "This cauldron already contains a brew.", NamedTextColor.RED);
            return false;
        }
        pots.put(k, new Pot());
        Location l = cauldron.getLocation().add(0.5, 0.8, 0.5);
        cauldron.getWorld().playSound(l, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.2f);
        cauldron.getWorld().spawnParticle(Particle.SPLASH, l, 10, 0.2, 0.05, 0.2, 0.0);
        msg(who, "The water turns yellowish. Place a lit campfire underneath.", NamedTextColor.YELLOW);
        return true;
    }

    private boolean tryMushroom(Block cauldron, Player who) {
        Pot pot = pots.get(Key.of(cauldron));
        if (pot == null) {
            msg(who, "Add potatoes to the water first.", NamedTextColor.RED);
            return false;
        }
        if (pot.stage != Stage.YELLOW) {
            msg(who, pot.stage == Stage.FERMENTING ? "It is already brewing." : "It is already finished.",
                    NamedTextColor.RED);
            return false;
        }
        if (!isHeated(cauldron) || pot.heat < STEAM_AFTER) {
            msg(who, "It has to be boiled over a lit campfire until it steams first.", NamedTextColor.RED);
            return false;
        }
        pot.stage = Stage.FERMENTING;
        pot.ferment = 0;
        Location l = cauldron.getLocation().add(0.5, 0.9, 0.5);
        cauldron.getWorld().playSound(l, Sound.ENTITY_GENERIC_SPLASH, 0.5f, 0.8f);
        msg(who, "The brew starts to ferment...", NamedTextColor.YELLOW);
        return true;
    }

    // ============================ Events ============================

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.WATER_CAULDRON) return;
        EquipmentSlot hand = e.getHand();
        ItemStack item = e.getItem();
        if (hand == null || item == null) return;
        Player p = e.getPlayer();

        switch (item.getType()) {
            case POTATO -> {
                e.setCancelled(true);
                if (tryPotato(b, p)) consume(p, hand);
            }
            case BROWN_MUSHROOM -> {
                e.setCancelled(true);
                if (tryMushroom(b, p)) consume(p, hand);
            }
            case GLASS_BOTTLE -> {
                Key k = Key.of(b);
                Pot pot = pots.get(k);
                if (pot == null) return; // normal cauldron -> vanilla behaviour
                e.setCancelled(true);
                if (pot.stage != Stage.READY) {
                    msg(p, "The brew is not ready yet.", NamedTextColor.RED);
                    return;
                }
                consume(p, hand);
                ItemStack potion = createHaraa();
                Map<Integer, ItemStack> left = p.getInventory().addItem(potion);
                for (ItemStack rest : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 1.0f, 1.0f);
                takeLevel(b, k);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        Material t = item.getItemStack().getType();
        if (t != Material.POTATO && t != Material.BROWN_MUSHROOM) return;
        Player thrower = e.getPlayer();

        new BukkitRunnable() {
            int age = 0;

            @Override
            public void run() {
                age += 2;
                if (!item.isValid() || age > 200) {
                    cancel();
                    return;
                }
                Block b = item.getLocation().getBlock();
                if (b.getType() != Material.WATER_CAULDRON) return;

                ItemStack st = item.getItemStack();
                boolean ok = st.getType() == Material.POTATO
                        ? tryPotato(b, thrower)
                        : tryMushroom(b, thrower);
                if (!ok) {
                    cancel();
                    return;
                }
                int left = st.getAmount() - 1;
                if (left <= 0) {
                    item.remove();
                    cancel();
                } else {
                    st.setAmount(left);
                    item.setItemStack(st);
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (it.getType() != Material.POTION || !it.hasItemMeta()) return;
        if (!it.getItemMeta().getPersistentDataContainer().has(potionKey, PersistentDataType.BYTE)) return;

        Player p = e.getPlayer();
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, DRUNK_NAUSEA_TICKS, 0, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, DRUNK_SLOW_TICKS, 0, false, true, true));
        msg(p, "You feel drunk...", NamedTextColor.GOLD);
    }

    // ============================ Helpers ============================

    private ItemStack createHaraa() {
        ItemStack s = new ItemStack(Material.POTION);
        PotionMeta m = (PotionMeta) s.getItemMeta();
        m.displayName(Component.text("Haraa", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("A cloudy, strong brew.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        m.setBasePotionType(PotionType.WATER);
        m.setColor(Color.fromRGB(235, 235, 225));
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        m.getPersistentDataContainer().set(potionKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta((ItemMeta) m);
        return s;
    }

    private void takeLevel(Block b, Key k) {
        Levelled l = (Levelled) b.getBlockData();
        int n = l.getLevel() - 1;
        if (n <= 0) {
            removePot(k);
            b.setType(Material.CAULDRON);
        } else {
            l.setLevel(n);
            b.setBlockData(l);
        }
    }

    private void consume(Player p, EquipmentSlot hand) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack s = p.getInventory().getItem(hand);
        if (s == null) return;
        if (s.getAmount() <= 1) {
            p.getInventory().setItem(hand, null);
        } else {
            s.setAmount(s.getAmount() - 1);
            p.getInventory().setItem(hand, s);
        }
    }

    private void msg(Player p, String text, NamedTextColor color) {
        if (p != null && p.isOnline()) p.sendActionBar(Component.text(text, color));
    }

    // ============================ Persistence ============================

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Key, Pot> en : pots.entrySet()) {
            Key k = en.getKey();
            Pot p = en.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", k.world().toString());
            m.put("x", k.x());
            m.put("y", k.y());
            m.put("z", k.z());
            m.put("stage", p.stage.name());
            m.put("heat", p.heat);
            m.put("ferment", p.ferment);
            list.add(m);
        }
        cfg.set("pots", list);
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save cauldrons.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        for (Map<?, ?> m : cfg.getMapList("pots")) {
            try {
                Key k = new Key(UUID.fromString((String) m.get("world")),
                        ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(),
                        ((Number) m.get("z")).intValue());
                Pot p = new Pot();
                p.stage = Stage.valueOf((String) m.get("stage"));
                p.heat = ((Number) m.get("heat")).intValue();
                p.ferment = ((Number) m.get("ferment")).intValue();
                pots.put(k, p);
            } catch (Exception ex) {
                getLogger().warning("Skipped a broken cauldron entry: " + ex.getMessage());
            }
        }
    }
}
