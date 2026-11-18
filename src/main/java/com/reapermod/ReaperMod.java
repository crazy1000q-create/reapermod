package com.reapermod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.equipment.ArmorMaterials;
import net.minecraft.item.equipment.EquipmentType;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Function;

public class ReaperMod implements ModInitializer {
    public static final String ID = "reapermod";
    static final EntityAttributeModifier.Operation ADD = EntityAttributeModifier.Operation.ADD_VALUE;

    static final Map<UUID, Long> IMMUNE = new HashMap<>();
    static final Map<UUID, Long> CRUMBLED = new HashMap<>();
    static final Map<UUID, Integer> LEAP = new HashMap<>();
    static final Map<UUID, Long> DOOM = new HashMap<>();
    static final Map<UUID, Long> ESSENCE = new HashMap<>();
    static final Map<UUID, Long> CHARGED = new HashMap<>();
    static boolean extra = false;

    public static Item TERRESTRIAL, ABSOLUTE_ZERO, BLIGHTSPORE, CATACLYSM, THE_FAVOR;
    public static Item REAPER_HELMET, REAPER_CHESTPLATE, REAPER_LEGGINGS, REAPER_BOOTS;

    // ---------- helpers ----------
    static Text c(String s) {
        return Text.literal(s.replace('&', '\u00a7')).setStyle(Style.EMPTY.withItalic(false));
    }

    static LoreComponent lore(String... lines) {
        List<Text> l = new ArrayList<>();
        for (String s : lines) l.add(c(s));
        return new LoreComponent(l);
    }


    static Text nm(Formatting color, String s) {
        return Text.literal(s).setStyle(Style.EMPTY.withColor(color).withItalic(false));
    }

    record Task(long at, Runnable run) {}
    static final List<Task> TASKS = new ArrayList<>();

    static void later(ServerWorld w, int ticks, Runnable r) { TASKS.add(new Task(w.getTime() + ticks, r)); }

    static void snd(ServerWorld w, Entity e, String id, float vol, float pitch) {
        SoundEvent se = Registries.SOUND_EVENT.get(Identifier.of("minecraft", id));
        if (se != null) w.playSound(null, e.getX(), e.getY(), e.getZ(), se, SoundCategory.PLAYERS, vol, pitch);
    }

    static void ring(ServerWorld w, Entity c, ParticleEffect pe, double r, int pts) {
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            w.spawnParticles(pe, c.getX() + Math.cos(a) * r, c.getY() + 0.2, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    static BlockStateParticleEffect blk(net.minecraft.block.Block b) {
        return new BlockStateParticleEffect(ParticleTypes.BLOCK, b.getDefaultState());
    }

    static long now(Entity e) { return e.getWorld().getTime(); }

    static List<LivingEntity> near(ServerWorld w, LivingEntity c, double r) {
        return w.getEntitiesByClass(LivingEntity.class, c.getBoundingBox().expand(r),
                e -> e != c && e.isAlive() && !(e instanceof ArmorStandEntity));
    }

    static void hit(ServerWorld w, ServerPlayerEntity p, LivingEntity e, float dmg) {
        e.timeUntilRegen = 0;
        e.damage(w, w.getDamageSources().playerAttack(p), dmg);
    }

    static void root(LivingEntity e, int ticks) {
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 6, false, true));
    }

    static Item reg(String id, Function<Item.Settings, Item> f, Item.Settings s) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(ID, id));
        return Registry.register(Registries.ITEM, key, f.apply(s.registryKey(key)));
    }

    static Item.Settings weapon(Text name, double dmg, double speedMod, String[] lore) {
        return new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
                .component(DataComponentTypes.CUSTOM_NAME, name)
                .component(DataComponentTypes.LORE, lore(lore))
                .attributeModifiers(AttributeModifiersComponent.builder()
                        .add(EntityAttributes.ATTACK_DAMAGE,
                                new EntityAttributeModifier(Identifier.ofVanilla("base_attack_damage"), dmg - 1, ADD),
                                AttributeModifierSlot.MAINHAND)
                        .add(EntityAttributes.ATTACK_SPEED,
                                new EntityAttributeModifier(Identifier.ofVanilla("base_attack_speed"), speedMod, ADD),
                                AttributeModifierSlot.MAINHAND)
                        .build());
    }

    static Item.Settings armor(EquipmentType type, AttributeModifierSlot slot, String id, Text name,
                               int armor, int hp, String[] lore) {
        Identifier base = Identifier.of(ID, id);
        return ArmorMaterials.NETHERITE.applySettings(new Item.Settings(), type).maxDamage(1000000).rarity(Rarity.EPIC)
                .component(DataComponentTypes.CUSTOM_NAME, name)
                .component(DataComponentTypes.LORE, lore(lore))
                .attributeModifiers(AttributeModifiersComponent.builder()
                        .add(EntityAttributes.ARMOR, new EntityAttributeModifier(base.withSuffixedPath("_armor"), armor, ADD), slot)
                        .add(EntityAttributes.ARMOR_TOUGHNESS, new EntityAttributeModifier(base.withSuffixedPath("_tough"), 8, ADD), slot)
                        .add(EntityAttributes.MAX_HEALTH, new EntityAttributeModifier(base.withSuffixedPath("_hp"), hp, ADD), slot)
                        .build());
    }

    interface Ability { boolean run(ServerWorld w, ServerPlayerEntity p); }

    static class AbilityItem extends Item {
        final Ability ability;
        final int cooldownSeconds;
        AbilityItem(Settings s, Ability a, int cd) { super(s); ability = a; cooldownSeconds = cd; }

        @Override
        public ActionResult use(World world, PlayerEntity user, Hand hand) {
            if (world instanceof ServerWorld sw && user instanceof ServerPlayerEntity sp) {
                if (ability.run(sw, sp)) {
                    user.getItemCooldownManager().set(user.getStackInHand(hand), cooldownSeconds * 20);
                }
            }
            return ActionResult.SUCCESS;
        }
    }

    // ---------- abilities ----------
    static boolean leapOfFaith(ServerWorld w, ServerPlayerEntity p) {
        IMMUNE.put(p.getUuid(), Long.MAX_VALUE);
        LEAP.put(p.getUuid(), 0);
        p.setVelocity(0, 1.4, 0);
        p.velocityModified = true;
        snd(w, p, "entity.firework_rocket.launch", 1f, 0.7f);
        snd(w, p, "entity.ender_dragon.flap", 1f, 1.2f);
        return true;
    }

    static void slam(ServerWorld w, ServerPlayerEntity p) {
        BlockStateParticleEffect stone = blk(Blocks.STONE);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
        for (int r = 1; r <= 6; r++) ring(w, p, stone, r, 30);
        snd(w, p, "entity.generic.explode", 1.2f, 0.8f);
        snd(w, p, "entity.iron_golem.damage", 1f, 0.6f);
        for (LivingEntity e : near(w, p, 6)) {
            hit(w, p, e, 1000f);
            w.spawnParticles(stone, e.getX(), e.getY() + 1, e.getZ(), 40, 0.3, 0.5, 0.3, 0.1);
            CRUMBLED.put(e.getUuid(), now(e) + 160);
            root(e, 160);
        }
    }

    static boolean frozenTomb(ServerWorld w, ServerPlayerEntity p) {
        IMMUNE.put(p.getUuid(), now(p) + 100);
        BlockStateParticleEffect ice = blk(Blocks.BLUE_ICE);
        snd(w, p, "block.glass.break", 1f, 0.5f);
        snd(w, p, "entity.player.hurt_freeze", 1f, 0.6f);
        for (int i = 0; i < 40; i += 4) {
            later(w, i, () -> {
                p.setFrozenTicks(160);
                for (int k = 0; k < 24; k++) {
                    double a = 2 * Math.PI * k / 24;
                    for (double h = 0; h <= 2; h += 0.5) {
                        w.spawnParticles(ice, p.getX() + Math.cos(a) * 1.2, p.getY() + h, p.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
                    }
                }
                w.spawnParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 1, p.getZ(), 20, 0.8, 0.8, 0.8, 0.02);
            });
        }
        Set<UUID> done = new HashSet<>();
        later(w, 40, () -> {
            snd(w, p, "block.glass.break", 1.5f, 0.4f);
            snd(w, p, "entity.player.hurt_freeze", 1.5f, 0.5f);
        });
        for (int r = 1; r <= 8; r++) {
            final int rr = r;
            later(w, 40 + r * 2, () -> {
                ring(w, p, ice, rr, 40);
                ring(w, p, ParticleTypes.SNOWFLAKE, rr, 40);
                for (LivingEntity e : near(w, p, rr)) {
                    if (done.add(e.getUuid())) {
                        hit(w, p, e, 250f);
                        root(e, 60);
                        e.setFrozenTicks(200);
                        e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 4, false, true));
                        w.spawnParticles(ice, e.getX(), e.getY() + 1, e.getZ(), 40, 0.3, 0.6, 0.3, 0.1);
                    }
                }
            });
        }
        return true;
    }

    static boolean bloodburst(ServerWorld w, ServerPlayerEntity p) {
        float max = p.getMaxHealth();
        if (p.getHealth() <= max * 0.1f + 1f) return false;
        p.setHealth(p.getHealth() - max * 0.1f);
        BlockStateParticleEffect blood = blk(Blocks.REDSTONE_BLOCK);
        snd(w, p, "entity.wither.hurt", 1f, 0.6f);
        snd(w, p, "entity.generic.explode", 0.8f, 1.4f);
        w.spawnParticles(blood, p.getX(), p.getY() + 1, p.getZ(), 150, 0.5, 0.8, 0.5, 0.3);
        for (int r = 1; r <= 8; r++) {
            final int rr = r;
            later(w, r, () -> {
                ring(w, p, blood, rr, 40);
                ring(w, p, ParticleTypes.DAMAGE_INDICATOR, rr, 20);
            });
        }
        for (LivingEntity e : near(w, p, 8)) {
            hit(w, p, e, max * 10f);
            w.spawnParticles(blood, e.getX(), e.getY() + 1, e.getZ(), 40, 0.3, 0.6, 0.3, 0.2);
        }
        return true;
    }

    static boolean doomsday(ServerWorld w, ServerPlayerEntity p) {
        root(p, 60);
        DOOM.put(p.getUuid(), now(p) + 60);
        snd(w, p, "entity.warden.sonic_charge", 1.5f, 0.8f);
        for (int t = 0; t < 60; t += 2) {
            final double r = 6 - t * 0.09;
            later(w, t, () -> {
                ring(w, p, ParticleTypes.PORTAL, r, 40);
                ring(w, p, ParticleTypes.SQUID_INK, r, 12);
                w.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 15, 0.3, 0.6, 0.3, 0.05);
            });
        }
        return true;
    }

    static void doomBlast(ServerWorld w, ServerPlayerEntity p) {
        snd(w, p, "entity.warden.sonic_boom", 1.5f, 0.7f);
        snd(w, p, "entity.generic.explode", 1.5f, 0.6f);
        w.spawnParticles(ParticleTypes.SONIC_BOOM, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        for (int r = 1; r <= 10; r++) {
            final int rr = r;
            later(w, r, () -> {
                ring(w, p, ParticleTypes.PORTAL, rr, 60);
                ring(w, p, ParticleTypes.SQUID_INK, rr, 20);
                ring(w, p, ParticleTypes.END_ROD, rr, 20);
            });
        }
        for (LivingEntity e : near(w, p, 10)) {
            hit(w, p, e, 650f + p.getHealth());
            w.spawnParticles(ParticleTypes.PORTAL, e.getX(), e.getY() + 1, e.getZ(), 60, 0.3, 0.6, 0.3, 0.5);
        }
    }

    static boolean stellarStrike(ServerWorld w, ServerPlayerEntity p) {
        CHARGED.put(p.getUuid(), now(p) + 400);
        snd(w, p, "block.beacon.activate", 1f, 1.5f);
        snd(w, p, "entity.player.levelup", 1f, 0.6f);
        w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 60, 0.5, 0.9, 0.5, 0.1);
        for (int r = 1; r <= 3; r++) ring(w, p, ParticleTypes.END_ROD, r, 30);
        return true;
    }

    // ---------- init ----------
    @Override
    public void onInitialize() {
        TERRESTRIAL = reg("terrestrial", s -> new AbilityItem(s, ReaperMod::leapOfFaith, 120),
                weapon(nm(Formatting.GREEN, "Terrestrial"), 158, -2.4, new String[]{
                        "&eRequires Fighting Lvl.20", "&7Crit Chance: &f+100%", "&7Parry Rating: &f+15%", "",
                        "&f&lLEAP OF FAITH &7(Right Click)",
                        "&7You turn &6immune &7to damage", "&7then &aleap upwards &7before",
                        "&cstriking &7the ground below.", "&7Targets hit take &c1,000 &7dmg",
                        "&7and are &6crumbled&7, taking &c25%", "&7more damage and are &6rooted", "&7for the duration.",
                        "&8| &b120s &8|", "", "&5&lMYTHIC"}));

        ABSOLUTE_ZERO = reg("absolute_zero", s -> new AbilityItem(s, ReaperMod::frozenTomb, 80),
                weapon(Text.empty().append(nm(Formatting.WHITE, "Absolute ")).append(nm(Formatting.AQUA, "Zero")), 142, -2.0, new String[]{
                        "&eRequires Fighting Lvl.20", "&7Defense: &f+50", "&9Tenacity: &b+30%", "",
                        "&f&lFROZEN TOMB &7(Right Click)",
                        "&7Entombs yourself in dark ice,", "&7becoming &6invulnerable &7for", "&7the duration. Dark ice then",
                        "&7spreads outwards, dealing", "&c250 &7damage and &dstunning", "&7them for &d3s&7.",
                        "&8| &b80s &8|", "", "&5&lMYTHIC"}));

        THE_FAVOR = reg("the_favor", s -> new AbilityItem(s, ReaperMod::stellarStrike, 60),
                weapon(nm(Formatting.WHITE, "The Favor"), 144, -2.0, new String[]{
                        "&eRequires Fighting Lvl.20", "",
                        "&f&lSTELLAR STRIKE &7(Right Click)",
                        "&7Charges your weapon with", "&7celestial energy, boosting",
                        "&7your next attack damage by", "&f500%&7. Boosted attack also",
                        "&7& &dstuns &7target for 3s",
                        "&8| &b60s &8|", "", "&5&lMYTHIC"}));

        BLIGHTSPORE = reg("blightspore", s -> new AbilityItem(s, ReaperMod::bloodburst, 5),
                weapon(nm(Formatting.DARK_PURPLE, "Blightspore"), 70, -2.4, new String[]{
                        "&cStaff", "&7Skill Crit Chance: &f+15%", "&7Skill Crit Power: &f+50%", "&7Defense: &f+50",
                        "&7Movement Speed: &f+5", "&7Has a &a100% &7chance to bind your item.",
                        "&7Level &eX &7Soulbound", "",
                        "&aEmpty &dSorcery Gem Socket", "&aEmpty &bTime Gem Socket", "",
                        "&f&lBLOODBURST &7(Right Click)",
                        "&7Consumes &c10% max \u2764HP &7to", "&7create a blood burst, which",
                        "&7deals &c10x &7your max &c\u2764HP &7as", "&7AOE damage.",
                        "&8| &b5s &8|", "", "&7Tier: &e&lGODLIKE"}));

        CATACLYSM = reg("the_cataclysm", s -> new AbilityItem(s, ReaperMod::doomsday, 85),
                weapon(nm(Formatting.LIGHT_PURPLE, "The Cataclysm"), 75, -2.4, new String[]{
                        "&cStaff", "&7Skill Crit Chance: &f+100%", "&7Skill Crit Power: &f+50%", "&7Range: &f15", "",
                        "&aEmpty &dSorcery Gem Socket", "&aEmpty &dSorcery Gem Socket", "&aEmpty &bTime Gem Socket", "",
                        "&f&lDOOMSDAY &7(Right Click)",
                        "&bRoot &7yourself for &b3 secs&7,", "&7then unleashes a &5void blast&7,",
                        "&7dealing &c650 + 100% \u2764 &7to", "&7nearby enemies.",
                        "&8| &b85s &8|", "", "&7Tier: &5&lMYTHIC"}));

        String[] set = {"", "&6&lREAPER SET &7(Helmet, Chestplate, Leggings, Boots)", "", "&7Tier: &6&lLEGENDARY"};
        REAPER_HELMET = reg("reaper_helmet", Item::new, armor(EquipmentType.HELMET, AttributeModifierSlot.HEAD,
                "reaper_helmet", nm(Formatting.YELLOW, "Reaper Helmet"), 10, 25, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +25",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));
        REAPER_CHESTPLATE = reg("reaper_chestplate", Item::new, armor(EquipmentType.CHESTPLATE, AttributeModifierSlot.CHEST,
                "reaper_chestplate", nm(Formatting.YELLOW, "Reaper Chestplate"), 10, 40, new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +40",
                        "&7Skill Cooldown Reduction: &f+5%", "",
                        "&f&lESSENCE THEFT &d\u25b2", "&7Slaying an enemy steals", "&7their essence, healing",
                        "&c10% max \u2764", "&8(2s cooldown)", "",
                        "&6&lREAPER SET &7(Helmet, Chestplate, Leggings, Boots)", "", "&7Tier: &6&lLEGENDARY"}));
        REAPER_LEGGINGS = reg("reaper_leggings", Item::new, armor(EquipmentType.LEGGINGS, AttributeModifierSlot.LEGS,
                "reaper_leggings", nm(Formatting.YELLOW, "Reaper Leggings"), 10, 35, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +35",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));
        REAPER_BOOTS = reg("reaper_boots", Item::new, armor(EquipmentType.BOOTS, AttributeModifierSlot.FEET,
                "reaper_boots", nm(Formatting.YELLOW, "Reaper Boots"), 10, 20, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +20",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(g -> {
            g.add(TERRESTRIAL); g.add(ABSOLUTE_ZERO); g.add(THE_FAVOR); g.add(BLIGHTSPORE); g.add(CATACLYSM);
            g.add(REAPER_HELMET); g.add(REAPER_CHESTPLATE); g.add(REAPER_LEGGINGS); g.add(REAPER_BOOTS);
        });

        // invulnerability (Leap of Faith / Frozen Tomb)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amt) -> {
            Long until = IMMUNE.get(e.getUuid());
            return until == null || until <= now(e);
        });

        // crumbled: +25% damage taken
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, src, base, taken, blocked) -> {
            Long until = CRUMBLED.get(e.getUuid());
            if (until == null || extra) return;
            if (until < now(e)) { CRUMBLED.remove(e.getUuid()); return; }
            if (e.getWorld() instanceof ServerWorld w) {
                extra = true;
                e.timeUntilRegen = 0;
                e.damage(w, src, taken * 0.25f);
                extra = false;
            }
        });

        // stellar strike: boosted next attack
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, src, base, taken, blocked) -> {
            if (extra || !(src.getAttacker() instanceof ServerPlayerEntity p) || src.getSource() != p) return;
            Long until = CHARGED.get(p.getUuid());
            if (until == null || !p.getMainHandStack().isOf(THE_FAVOR)) return;
            CHARGED.remove(p.getUuid());
            if (until < now(p) || !(e.getWorld() instanceof ServerWorld w)) return;
            extra = true;
            e.timeUntilRegen = 0;
            e.damage(w, src, taken * 5f);
            extra = false;
            root(e, 60);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 4, false, true));
            snd(w, e, "entity.generic.explode", 1f, 1.5f);
            snd(w, e, "entity.lightning_bolt.impact", 1f, 1.8f);
            w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1, e.getZ(), 80, 0.3, 0.8, 0.3, 0.3);
            w.spawnParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY() + 1, e.getZ(), 3, 0.3, 0.5, 0.3, 0);
        });

        // essence theft (chestplate)
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (src.getAttacker() instanceof ServerPlayerEntity p
                    && p.getEquippedStack(EquipmentSlot.CHEST).isOf(REAPER_CHESTPLATE)) {
                Long last = ESSENCE.get(p.getUuid());
                if (last == null || now(p) - last >= 40) {
                    ESSENCE.put(p.getUuid(), now(p));
                    p.heal(p.getMaxHealth() * 0.1f);
                }
            }
        });

        // leap + doomsday timers
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long nowT = server.getOverworld().getTime();
            if (server.getTicks() % 10 == 0) {
                for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                    int n = 0;
                    if (pl.getEquippedStack(EquipmentSlot.HEAD).isOf(REAPER_HELMET)) n++;
                    if (pl.getEquippedStack(EquipmentSlot.CHEST).isOf(REAPER_CHESTPLATE)) n++;
                    if (pl.getEquippedStack(EquipmentSlot.LEGS).isOf(REAPER_LEGGINGS)) n++;
                    if (pl.getEquippedStack(EquipmentSlot.FEET).isOf(REAPER_BOOTS)) n++;
                    int lvl = n >= 4 ? 2 : (n >= 2 ? 1 : (n == 1 ? 0 : -1));
                    if (pl.getMainHandStack().isOf(ABSOLUTE_ZERO) || pl.getMainHandStack().isOf(BLIGHTSPORE)) {
                        lvl = Math.min(3, lvl + 1);
                        if (lvl < 0) lvl = 0;
                    }
                    if (pl.getMainHandStack().isOf(BLIGHTSPORE)) {
                        pl.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30, 3, true, false, false));
                    }
                    if (lvl >= 0) {
                        pl.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 30, lvl, true, false, false));
                    }
                }
            }
            for (Task k : new ArrayList<>(TASKS)) {
                if (k.at() <= nowT) { TASKS.remove(k); k.run().run(); }
            }
            Iterator<Map.Entry<UUID, Integer>> it = LEAP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> en = it.next();
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
                if (p == null) { IMMUNE.remove(en.getKey()); it.remove(); continue; }
                int t = en.getValue() + 1;
                p.getServerWorld().spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 5, 0.2, 0.1, 0.2, 0.02);
                en.setValue(t);
                if (t == 10) {
                    p.setVelocity(0, -3, 0);
                    p.velocityModified = true;
                } else if ((t > 10 && p.isOnGround()) || t > 100) {
                    it.remove();
                    IMMUNE.remove(p.getUuid());
                    slam(p.getServerWorld(), p);
                }
            }
            Iterator<Map.Entry<UUID, Long>> d = DOOM.entrySet().iterator();
            while (d.hasNext()) {
                Map.Entry<UUID, Long> en = d.next();
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
                if (p == null) { d.remove(); continue; }
                if (now(p) >= en.getValue()) {
                    d.remove();
                    doomBlast(p.getServerWorld(), p);
                }
            }
        });
    }

    static String[] concat(String[] a, String[] b) {
        String[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
