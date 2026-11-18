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
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.equipment.ArmorMaterials;
import net.minecraft.item.equipment.EquipmentType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
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
    static boolean extra = false;

    public static Item TERRESTRIAL, ABSOLUTE_ZERO, BLIGHTSPORE, CATACLYSM;
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

    static Item.Settings weapon(String name, double dmg, double speedMod, String[] lore) {
        return new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
                .component(DataComponentTypes.ITEM_NAME, c(name))
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

    static Item.Settings armor(EquipmentType type, AttributeModifierSlot slot, String id, String name,
                               int armor, int hp, String[] lore) {
        Identifier base = Identifier.of(ID, id);
        return ArmorMaterials.NETHERITE.applySettings(new Item.Settings(), type)
                .component(DataComponentTypes.ITEM_NAME, c(name))
                .component(DataComponentTypes.LORE, lore(lore))
                .attributeModifiers(AttributeModifiersComponent.builder()
                        .add(EntityAttributes.ARMOR, new EntityAttributeModifier(base.withSuffixedPath("_armor"), armor, ADD), slot)
                        .add(EntityAttributes.ARMOR_TOUGHNESS, new EntityAttributeModifier(base.withSuffixedPath("_tough"), 3, ADD), slot)
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
        return true;
    }

    static void slam(ServerWorld w, ServerPlayerEntity p) {
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
        for (LivingEntity e : near(w, p, 6)) {
            hit(w, p, e, 1000f);
            CRUMBLED.put(e.getUuid(), now(e) + 160);
            root(e, 160);
        }
    }

    static boolean frozenTomb(ServerWorld w, ServerPlayerEntity p) {
        IMMUNE.put(p.getUuid(), now(p) + 100);
        w.spawnParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 1, p.getZ(), 300, 4, 1, 4, 0.1);
        for (LivingEntity e : near(w, p, 8)) {
            hit(w, p, e, 250f);
            root(e, 60);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 4, false, true));
        }
        return true;
    }

    static boolean bloodburst(ServerWorld w, ServerPlayerEntity p) {
        float max = p.getMaxHealth();
        if (p.getHealth() <= max * 0.1f + 1f) return false;
        p.setHealth(p.getHealth() - max * 0.1f);
        w.spawnParticles(ParticleTypes.CRIMSON_SPORE, p.getX(), p.getY() + 1, p.getZ(), 200, 4, 1, 4, 0.1);
        for (LivingEntity e : near(w, p, 8)) hit(w, p, e, max * 10f);
        return true;
    }

    static boolean doomsday(ServerWorld w, ServerPlayerEntity p) {
        root(p, 60);
        DOOM.put(p.getUuid(), now(p) + 60);
        return true;
    }

    static void doomBlast(ServerWorld w, ServerPlayerEntity p) {
        w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 400, 5, 1, 5, 0.5);
        for (LivingEntity e : near(w, p, 10)) hit(w, p, e, 650f + p.getHealth());
    }

    // ---------- init ----------
    @Override
    public void onInitialize() {
        TERRESTRIAL = reg("terrestrial", s -> new AbilityItem(s, ReaperMod::leapOfFaith, 120),
                weapon("&aTerrestrial", 158, -2.4, new String[]{
                        "&eRequires Fighting Lvl.20", "&7Crit Chance: &f+100%", "&7Parry Rating: &f+15%", "",
                        "&f&lLEAP OF FAITH &7(Right Click)",
                        "&7You turn &6immune &7to damage", "&7then &aleap upwards &7before",
                        "&cstriking &7the ground below.", "&7Targets hit take &c1,000 &7dmg",
                        "&7and are &6crumbled&7, taking &c25%", "&7more damage and are &6rooted", "&7for the duration.",
                        "&8| &b120s &8|", "", "&5&lMYTHIC"}));

        ABSOLUTE_ZERO = reg("absolute_zero", s -> new AbilityItem(s, ReaperMod::frozenTomb, 80),
                weapon("&bAbsolute Zero", 142, -2.0, new String[]{
                        "&eRequires Fighting Lvl.20", "&7Defense: &f+50", "&9Tenacity: &b+30%", "",
                        "&f&lFROZEN TOMB &7(Right Click)",
                        "&7Entombs yourself in dark ice,", "&7becoming &6invulnerable &7for", "&7the duration. Dark ice then",
                        "&7spreads outwards, dealing", "&c250 &7damage and &dstunning", "&7them for &d3s&7.",
                        "&8| &b80s &8|", "", "&5&lMYTHIC"}));

        BLIGHTSPORE = reg("blightspore", s -> new AbilityItem(s, ReaperMod::bloodburst, 5),
                weapon("&5Blightspore", 70, -2.4, new String[]{
                        "&cStaff", "&7Skill Crit Chance: &f+15%", "&7Skill Crit Power: &f+50%", "&7Defense: &f+50",
                        "&7Movement Speed: &f+5", "&7Has a &a100% &7chance to bind your item.",
                        "&7Level &eX &7Soulbound", "",
                        "&aEmpty &dSorcery Gem Socket", "&aEmpty &bTime Gem Socket", "",
                        "&f&lBLOODBURST &7(Right Click)",
                        "&7Consumes &c10% max \u2764HP &7to", "&7create a blood burst, which",
                        "&7deals &c10x &7your max &c\u2764HP &7as", "&7AOE damage.",
                        "&8| &b5s &8|", "", "&7Tier: &e&lGODLIKE"}));

        CATACLYSM = reg("the_cataclysm", s -> new AbilityItem(s, ReaperMod::doomsday, 85),
                weapon("&dThe Cataclysm", 75, -2.4, new String[]{
                        "&cStaff", "&7Skill Crit Chance: &f+100%", "&7Skill Crit Power: &f+50%", "&7Range: &f15", "",
                        "&aEmpty &dSorcery Gem Socket", "&aEmpty &dSorcery Gem Socket", "&aEmpty &bTime Gem Socket", "",
                        "&f&lDOOMSDAY &7(Right Click)",
                        "&bRoot &7yourself for &b3 secs&7,", "&7then unleashes a &5void blast&7,",
                        "&7dealing &c650 + 100% \u2764 &7to", "&7nearby enemies.",
                        "&8| &b85s &8|", "", "&7Tier: &5&lMYTHIC"}));

        String[] set = {"", "&6&lREAPER SET &7(Helmet, Chestplate, Leggings, Boots)", "", "&7Tier: &6&lLEGENDARY"};
        REAPER_HELMET = reg("reaper_helmet", Item::new, armor(EquipmentType.HELMET, AttributeModifierSlot.HEAD,
                "reaper_helmet", "&eReaper Helmet", 3, 25, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +25",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));
        REAPER_CHESTPLATE = reg("reaper_chestplate", Item::new, armor(EquipmentType.CHESTPLATE, AttributeModifierSlot.CHEST,
                "reaper_chestplate", "&eReaper Chestplate", 8, 40, new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +40",
                        "&7Skill Cooldown Reduction: &f+5%", "",
                        "&f&lESSENCE THEFT &d\u25b2", "&7Slaying an enemy steals", "&7their essence, healing",
                        "&c10% max \u2764", "&8(2s cooldown)", "",
                        "&6&lREAPER SET &7(Helmet, Chestplate, Leggings, Boots)", "", "&7Tier: &6&lLEGENDARY"}));
        REAPER_LEGGINGS = reg("reaper_leggings", Item::new, armor(EquipmentType.LEGGINGS, AttributeModifierSlot.LEGS,
                "reaper_leggings", "&eReaper Leggings", 6, 35, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +35",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));
        REAPER_BOOTS = reg("reaper_boots", Item::new, armor(EquipmentType.BOOTS, AttributeModifierSlot.FEET,
                "reaper_boots", "&eReaper Boots", 3, 20, concat(new String[]{
                        "&cArmor", "&7Skill Damage: &f+25%", "&7Defense: &f+70", "&c\u2764 Health: +20",
                        "&7Skill Cooldown Reduction: &f+5%"}, set)));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(g -> {
            g.add(TERRESTRIAL); g.add(ABSOLUTE_ZERO); g.add(BLIGHTSPORE); g.add(CATACLYSM);
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
            Iterator<Map.Entry<UUID, Integer>> it = LEAP.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> en = it.next();
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
                if (p == null) { IMMUNE.remove(en.getKey()); it.remove(); continue; }
                int t = en.getValue() + 1;
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
