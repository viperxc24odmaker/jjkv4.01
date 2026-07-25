package com.makeforge.jjk.util;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/**
 * ==========================================================================
 *  ALL THE VERSION-SENSITIVE CALLS LIVE HERE.
 *  If the first CI build fails, it will almost certainly be in THIS file
 *  (damage signature, particle send, lightning/wolf create, effect holders).
 *  Fixing it means touching one method, not the whole mod.
 * ==========================================================================
 */
public final class CombatUtil {
    private CombatUtil() {}

    /* ---- targeting ---- */

    /** First living entity the player is looking at within reach, or null. */
    public static LivingEntity raycastLiving(ServerPlayer p, double reach) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(reach));
        AABB box = p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        LivingEntity best = null;
        double bestD = reach * reach;
        for (Entity e : p.level().getEntities(p, box)) {
            if (!(e instanceof LivingEntity le) || e == p) continue;
            // approximate: distance from the ray using the entity center
            Vec3 toE = e.position().subtract(eye);
            double t = toE.dot(look);
            if (t < 0 || t > reach) continue;
            Vec3 closest = eye.add(look.scale(t));
            double d = closest.distanceToSqr(e.position().add(0, e.getBbHeight() * 0.5, 0));
            if (d < 1.6 && closest.distanceToSqr(eye) < bestD) {
                best = le;
            }
        }
        return best;
    }

    /** Living entities inside a forward cone (angleDeg = half-angle). */
    public static List<LivingEntity> cone(ServerPlayer p, double range, double angleDeg) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0f).normalize();
        double cos = Math.cos(Math.toRadians(angleDeg));
        AABB box = p.getBoundingBox().inflate(range);
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.level().getEntities(p, box)) {
            if (!(e instanceof LivingEntity le) || e == p) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            if (to.length() > range) continue;
            if (to.normalize().dot(look) >= cos) out.add(le);
        }
        return out;
    }

    /** Living entities within radius of a point. */
    public static List<LivingEntity> around(ServerLevel level, ServerPlayer owner, Vec3 center, double radius) {
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : level.getEntities(owner, box)) {
            if (e instanceof LivingEntity le && e != owner
                    && le.position().distanceToSqr(center) <= radius * radius) {
                out.add(le);
            }
        }
        return out;
    }

    /* ---- effects on the world ---- */

    /** Deal magic (cursed) damage. NOTE: 1.21.5+ uses hurtServer(ServerLevel,..). */
    /**
     * Global sorcerer damage multiplier. Every ability routes its damage through
     * magicDamage/physicalDamage, so this one constant scales all 25 characters
     * at once. 1.15f = +15%. Set to 1.0f to return to baseline.
     */
    public static final float SORCERER_BUFF = 1.15f;

    public static void magicDamage(ServerLevel level, ServerPlayer source, LivingEntity target, float amount) {
        DamageSource ds = level.damageSources().indirectMagic(source, source);
        target.hurtServer(level, ds, amount * SORCERER_BUFF);
    }

    /** Deal straight physical damage as if a player hit. */
    public static void physicalDamage(ServerLevel level, ServerPlayer source, LivingEntity target, float amount) {
        DamageSource ds = level.damageSources().playerAttack(source);
        target.hurtServer(level, ds, amount * SORCERER_BUFF);
    }

    /** Knock an entity away from a point. Works on players too via impulse flag. */
    public static void knockAway(LivingEntity e, Vec3 from, double strength, double lift) {
        Vec3 dir = e.position().subtract(from);
        if (dir.lengthSqr() < 1.0e-4) dir = new Vec3(0, 1, 0);
        dir = dir.normalize();
        e.setDeltaMovement(dir.x * strength, lift, dir.z * strength);
        e.hurtMarked = true;      // forces velocity sync to clients
    }

    /** Pull an entity toward a point. */
    public static void pullToward(LivingEntity e, Vec3 to, double strength) {
        Vec3 dir = to.subtract(e.position());
        if (dir.lengthSqr() < 1.0e-4) return;
        dir = dir.normalize();
        e.setDeltaMovement(dir.x * strength, dir.y * strength * 0.5 + 0.15, dir.z * strength);
        e.hurtMarked = true;
    }

    public static void effect(LivingEntity e, Holder<MobEffect> effect, int ticks, int amp) {
        e.addEffect(new MobEffectInstance(effect, ticks, amp));
    }

    public static void particles(ServerLevel level, ParticleOptions particle, Vec3 at,
                                 int count, double spread, double speed) {
        level.sendParticles(particle, at.x, at.y, at.z, count, spread, spread, spread, speed);
    }

    /* ---- v3 juice: sounds + shaped particle effects ---- */

    /**
     * Play a sound at a point.
     * NOTE (confirmed against 1.21.11 by a real build): SoundEvents.* constants are
     * plain SoundEvent, NOT Holder<SoundEvent>. The one exception is GENERIC_EXPLODE,
     * which IS a Holder and has to be unwrapped with .value() at the call site.
     */
    public static void sound(ServerLevel level, Vec3 at, SoundEvent snd, float vol, float pitch) {
        level.playSound(null, at.x, at.y, at.z, snd, SoundSource.PLAYERS, vol, pitch);
    }

    /** A flat horizontal ring of particles — great for shockwaves / domain edges. */
    public static void ring(ServerLevel level, ParticleOptions particle, Vec3 center,
                            double radius, int points) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 / points) * i;
            Vec3 at = center.add(Math.cos(a) * radius, 0.1, Math.sin(a) * radius);
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /** A rising spiral column — used for awakening bursts. */
    public static void spiral(ServerLevel level, ParticleOptions particle, Vec3 base,
                              double radius, double height, int points) {
        for (int i = 0; i < points; i++) {
            double f = (double) i / points;
            double a = f * Math.PI * 6;
            Vec3 at = base.add(Math.cos(a) * radius, f * height, Math.sin(a) * radius);
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /** A straight particle beam from a point along a direction. */
    public static void beam(ServerLevel level, ParticleOptions particle, Vec3 from,
                            Vec3 dir, double length, int density) {
        Vec3 d = dir.normalize();
        int steps = (int) (length * density);
        for (int i = 1; i <= steps; i++) {
            Vec3 at = from.add(d.scale((double) i / density));
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /**
     * Teleport an entity. Isolated here because the teleport API is one of the
     * churniest things across 1.21.x — if CI complains, this is the only fix site.
     */
    public static void tp(net.minecraft.world.entity.Entity e, Vec3 to) {
        e.teleportTo(to.x, to.y, to.z);
    }

    /** A quick sphere-ish burst. */
    public static void burst(ServerLevel level, ParticleOptions particle, Vec3 at,
                             int count, double spread) {
        level.sendParticles(particle, at.x, at.y + 0.5, at.z, count, spread, spread, spread, 0.08);
    }

    public static void lightning(ServerLevel level, Vec3 at) {
        LightningBolt bolt = new LightningBolt(EntityType.LIGHTNING_BOLT, level);
        bolt.setPos(at.x, at.y, at.z);
        bolt.setVisualOnly(false);
        level.addFreshEntity(bolt);
    }

    /**
     * Spawn a buffed wolf near the player and point it at a target.
     * Kept deliberately simple: no owner-reference API (that signature has
     * churned across 1.21.x). The wolf is tamed so it won't wander/despawn
     * awkwardly, and we set its attack target directly.
     */
    public static Wolf summonWolf(ServerLevel level, ServerPlayer owner, Vec3 at, LivingEntity target) {
        Wolf wolf = new Wolf(EntityType.WOLF, level);
        wolf.setPos(at.x, at.y, at.z);
        wolf.setTame(true, true);
        wolf.setHealth(wolf.getMaxHealth());
        if (target != null) wolf.setTarget(target);
        level.addFreshEntity(wolf);
        return wolf;
    }
}
