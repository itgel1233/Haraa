package com.haraa;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import org.bukkit.util.Vector;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class HaraaPlugin extends JavaPlugin implements Listener {

    // ---------------- Settings (ticks: 20 ticks = 1 second) ----------------
    private static final int TICK = 5;                    // how often the cauldrons are processed
    private static final int STEAM_AFTER = 160;           // 8 s of heat until it "steams up"
    private static final int FERMENT_TIME = 200;          // 10 s after the mushroom is added

    // Drunk system. Index = drunk level (number of bottles drunk while still drunk), index 0 unused.
    private static final int MAX_DRUNK_TICKS = 20 * 120;   // you can never be drunk longer than 2 minutes
    private static final int PER_BOTTLE_TICKS = 20 * 60;   // each bottle adds 1 minute (capped at 2 minutes)
    private static final int MAX_LEVEL = 4;                // 4th bottle and beyond = level 4
    // Nausea "waves" (random): seconds between waves, wave length in seconds, nausea amplifier (2 = level III)
    private static final int[] NAUSEA_MIN = {0, 20, 10, 8, 6};
    private static final int[] NAUSEA_MAX = {0, 40, 20, 15, 12};
    private static final int[] NAUSEA_LEN = {0, 4, 6, 8, 10};
    private static final int[] NAUSEA_AMP = {0, 0, 0, 1, 2};
    // Level 3+: stumbling and tripping (seconds between events)
    private static final int[] STUMBLE_MIN = {0, 0, 0, 4, 3};
    private static final int[] STUMBLE_MAX = {0, 0, 0, 8, 6};
    private static final int[] TRIP_MIN = {0, 0, 0, 25, 15};
    private static final int[] TRIP_MAX = {0, 0, 0, 45, 30};
    // Level 4: blackouts (seconds between blackouts, blackout length in seconds)
    private static final int BLACKOUT_MIN = 20;
    private static final int BLACKOUT_MAX = 40;
    private static final int BLACKOUT_LEN_MIN = 3;
    private static final int BLACKOUT_LEN_MAX = 5;
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
        Bukkit.getScheduler().runTaskTimer(this, this::drunkTick, TICK, TICK);
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
        if (e.isCancelled()) return;
        ItemStack it = e.getItem();
        if (it.getType() != Material.POTION || !it.hasItemMeta()) return;
        if (!it.getItemMeta().getPersistentDataContainer().has(potionKey, PersistentDataType.BYTE)) return;

        drink(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent e) {
        Drunk d = drunk.get(e.getPlayer().getUniqueId());
        if (d == null || d.level < 3) return;
        String plain = PlainTextComponentSerializer.plainText().serialize(e.message());
        e.message(Component.text(slur(plain, d.level)));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        drunk.remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        drunk.remove(e.getPlayer().getUniqueId());
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

    // ============================ Drunk system ============================

    private static final class Drunk {
        volatile int level = 0;
        int remaining = 0;
        int nauseaIn = 40;
        int stumbleIn = -1;
        int tripIn = -1;
        int blackoutIn = -1;
        int blackoutLeft = 0;
        Location last;
    }

    private final Map<UUID, Drunk> drunk = new ConcurrentHashMap<>();

    private static int secs(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min * 20, max * 20 + 1);
    }

    private void drink(Player p) {
        Drunk d = drunk.computeIfAbsent(p.getUniqueId(), k -> new Drunk());
        d.level = Math.min(MAX_LEVEL, d.level + 1);
        d.remaining = Math.min(MAX_DRUNK_TICKS, d.remaining + PER_BOTTLE_TICKS);
        d.nauseaIn = Math.min(d.nauseaIn, 40);
        if (d.level >= 3) {
            if (d.stumbleIn < 0) d.stumbleIn = secs(STUMBLE_MIN[d.level], STUMBLE_MAX[d.level]);
            if (d.tripIn < 0) d.tripIn = secs(TRIP_MIN[d.level], TRIP_MAX[d.level]);
        }
        if (d.level >= 4 && d.blackoutIn < 0) d.blackoutIn = secs(BLACKOUT_MIN, BLACKOUT_MAX);

        switch (d.level) {
            case 1 -> msg(p, "You feel a little tipsy...", NamedTextColor.GOLD);
            case 2 -> msg(p, "You are getting drunk...", NamedTextColor.GOLD);
            case 3 -> msg(p, "You are very drunk. Walking straight is getting hard.", NamedTextColor.RED);
            default -> msg(p, "You are completely wasted...", NamedTextColor.DARK_RED);
        }
    }

    private void drunkTick() {
        Iterator<Map.Entry<UUID, Drunk>> it = drunk.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Drunk> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            Drunk d = en.getValue();

            d.remaining -= TICK;
            if (d.remaining <= 0) {
                it.remove();
                msg(p, "You sober up.", NamedTextColor.GRAY);
                continue;
            }

            // horizontal movement direction since the last tick (null = standing still)
            Location now = p.getLocation();
            Vector move = null;
            if (d.last != null && d.last.getWorld() == now.getWorld()) {
                double mx = now.getX() - d.last.getX();
                double mz = now.getZ() - d.last.getZ();
                if (mx * mx + mz * mz > 0.0025) move = new Vector(mx, 0, mz).normalize();
            }
            d.last = now;

            if (d.blackoutLeft > 0) {
                d.blackoutLeft -= TICK;
                if (d.blackoutLeft <= 0) {
                    d.blackoutLeft = 0;
                    msg(p, "You wake up with a pounding head...", NamedTextColor.GRAY);
                }
                continue;
            }

            int lvl = d.level;

            // random nausea waves (all levels)
            d.nauseaIn -= TICK;
            if (d.nauseaIn <= 0) {
                int dur = Math.min(NAUSEA_LEN[lvl] * 20, d.remaining);
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, dur, NAUSEA_AMP[lvl], false, true, true));
                d.nauseaIn = secs(NAUSEA_MIN[lvl], NAUSEA_MAX[lvl]);
            }

            // level 3+: stumbling and tripping
            if (lvl >= 3) {
                d.stumbleIn -= TICK;
                if (d.stumbleIn <= 0) {
                    d.stumbleIn = stumble(p, move) ? secs(STUMBLE_MIN[lvl], STUMBLE_MAX[lvl]) : 20;
                }
                d.tripIn -= TICK;
                if (d.tripIn <= 0) {
                    d.tripIn = trip(p, move) ? secs(TRIP_MIN[lvl], TRIP_MAX[lvl]) : 20;
                }
            }

            // level 4: blackouts
            if (lvl >= 4) {
                d.blackoutIn -= TICK;
                if (d.blackoutIn <= 0) {
                    d.blackoutIn = blackout(p, d) ? secs(BLACKOUT_MIN, BLACKOUT_MAX) : 60;
                }
            }
        }
    }

    private boolean cannotWobble(Player p) {
        return p.isDead() || !p.isOnGround() || p.isSneaking() || p.isFlying() || p.isInsideVehicle()
                || p.isInWater() || p.isInLava() || p.isGliding() || p.isClimbing() || p.isSwimming();
    }

    private static boolean isHazard(Material t) {
        return t == Material.LAVA || t == Material.FIRE || t == Material.SOUL_FIRE
                || t == Material.MAGMA_BLOCK || t == Material.CACTUS || t == Material.CAMPFIRE
                || t == Material.SOUL_CAMPFIRE || t == Material.POWDER_SNOW;
    }

    /** Safety check so being drunk never pushes anyone off a cliff or into lava. */
    private boolean hasGround(Location dest) {
        Block b = dest.getBlock();
        if (isHazard(b.getType())) return false;
        for (int i = 1; i <= 3; i++) {
            Material t = b.getRelative(0, -i, 0).getType();
            if (isHazard(t)) return false;
            if (t.isSolid() || t == Material.WATER) return true;
        }
        return false;
    }

    private boolean stumble(Player p, Vector move) {
        if (move == null || cannotWobble(p)) return false;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double sign = r.nextBoolean() ? 1 : -1;
        Vector side = new Vector(-move.getZ() * sign, 0, move.getX() * sign);
        side.rotateAroundY(Math.toRadians(r.nextDouble(-30, 30)));
        if (!hasGround(p.getLocation().add(side.clone().multiply(2.0)))) return false;
        p.setVelocity(side.multiply(0.22));
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 2, 0.2, 0.0, 0.2, 0.01);
        return true;
    }

    private boolean trip(Player p, Vector move) {
        if (move == null || cannotWobble(p)) return false;
        Location dest = p.getLocation().add(move.clone().multiply(2.5));
        if (hasGround(dest)) {
            Vector v = move.clone().multiply(0.35);
            v.setY(0.2);
            p.setVelocity(v);
        }
        p.playHurtAnimation(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, false, false, true));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_SMALL_FALL, 1.0f, 0.8f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 8, 0.3, 0.0, 0.3, 0.02);
        msg(p, "You tripped over your own feet!", NamedTextColor.RED);
        return true;
    }

    private boolean blackout(Player p, Drunk d) {
        if (p.isDead() || p.isInWater() || p.isInLava() || p.isFlying() || p.isInsideVehicle()
                || p.isGliding() || p.isClimbing() || p.isSwimming()) return false;
        int len = Math.min(secs(BLACKOUT_LEN_MIN, BLACKOUT_LEN_MAX), d.remaining);
        if (len < 20) return false;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, len, 0, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, len, 6, false, false, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, len, 2, false, false, true));
        d.blackoutLeft = len;
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.8f);
        msg(p, "You black out...", NamedTextColor.DARK_GRAY);
        return true;
    }

    private String slur(String in, int level) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double stretch = level >= 4 ? 0.25 : 0.12;
        double hiccup = level >= 4 ? 0.20 : 0.08;
        String[] words = in.split(" ", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (i > 0) out.append(' ');
            if (w.isEmpty()) continue;
            if (level >= 4 && w.length() > 1 && Character.isLetter(w.charAt(0)) && r.nextDouble() < 0.2) {
                out.append(w.charAt(0)).append('-');
            }
            for (int j = 0; j < w.length(); j++) {
                char c = w.charAt(j);
                if ((c == 's' || c == 'S') && r.nextDouble() < 0.5) {
                    out.append(c).append('h');
                } else if (Character.isLetter(c) && r.nextDouble() < stretch) {
                    out.append(c).append(c);
                    if (level >= 4 && r.nextDouble() < 0.5) out.append(c);
                } else {
                    out.append(c);
                }
            }
            if (r.nextDouble() < hiccup) out.append(" *hic*");
        }
        return out.toString();
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
