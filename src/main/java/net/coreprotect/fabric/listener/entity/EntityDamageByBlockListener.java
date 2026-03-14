package net.coreprotect.fabric.listener.entity;

import net.minecraft.entity.damage.DamageSource;

public final class EntityDamageByBlockListener {
    private EntityDamageByBlockListener() {
    }

    public static String actorFromDamageSource(DamageSource source) {
        if (source == null) {
            return "#entity";
        }

        String name = source.getName();
        if (name == null || name.isBlank()) {
            return "#entity";
        }

        String normalized = name.toLowerCase().replace(':', '_').replace('.', '_');
        if (normalized.contains("tnt")) {
            return "#tnt";
        }
        return "#" + normalized;
    }
}
