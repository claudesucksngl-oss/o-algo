package dev.payauction;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side fireworks show for the winner. Only you see it (it's your mod, not the server's).
 * "mid" = a volley of fireworks + title, "max" = a big show + totem pop with the item, "off" = nothing.
 */
public final class WinEffects {
    private WinEffects() {}

    private static final int[] PALETTE = {
            0xFFD700, 0xFF5555, 0x55FF55, 0x55FFFF, 0xFF55FF, 0xFFAA00, 0xFFFFFF, 0x5555FF
    };
    private static final FireworkExplosionComponent.Type[] SHAPES = {
            FireworkExplosionComponent.Type.LARGE_BALL, FireworkExplosionComponent.Type.SMALL_BALL,
            FireworkExplosionComponent.Type.STAR, FireworkExplosionComponent.Type.BURST
    };

    /** A fake rocket that flies up drawing a spark trail, then bursts. */
    private static final class Rocket {
        double x, y, z, vx, vy, vz;
        int delay, life;
        List<FireworkExplosionComponent> burst;
    }

    private static final List<Rocket> rockets = new ArrayList<>();

    public static void play(MinecraftClient client, String level, ItemStack item, String winner, String price) {
        if (client.player == null || "off".equalsIgnoreCase(level)) return;
        boolean max = "max".equalsIgnoreCase(level);

        client.inGameHud.setTitleTicks(5, 50, 15);
        client.inGameHud.setTitle(Text.literal("SOLD!").formatted(Formatting.GOLD, Formatting.BOLD));
        client.inGameHud.setSubtitle(Text.literal(winner + " won for " + price).formatted(Formatting.YELLOW));
        client.player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        if (max) client.gameRenderer.showFloatingItem(item.copy());

        Random r = client.player.getRandom();
        int count = max ? 14 : 6;
        for (int i = 0; i < count; i++) {
            Rocket k = new Rocket();
            double ang = r.nextDouble() * Math.PI * 2;
            double dist = 3 + r.nextDouble() * (max ? 6 : 4);
            k.x = client.player.getX() + Math.cos(ang) * dist;
            k.y = client.player.getY();
            k.z = client.player.getZ() + Math.sin(ang) * dist;
            k.vx = (r.nextDouble() - 0.5) * 0.08;
            k.vz = (r.nextDouble() - 0.5) * 0.08;
            k.vy = 0.55 + r.nextDouble() * 0.25;
            k.delay = i * (max ? 4 : 7) + r.nextInt(4);
            k.life = 16 + r.nextInt(8);
            k.burst = max ? List.of(randomExplosion(r), randomExplosion(r)) : List.of(randomExplosion(r));
            rockets.add(k);
        }
    }

    private static FireworkExplosionComponent randomExplosion(Random r) {
        int a = PALETTE[r.nextInt(PALETTE.length)];
        int b = PALETTE[r.nextInt(PALETTE.length)];
        int fade = PALETTE[r.nextInt(PALETTE.length)];
        return new FireworkExplosionComponent(SHAPES[r.nextInt(SHAPES.length)],
                IntList.of(a, b), IntList.of(fade), r.nextBoolean(), r.nextBoolean());
    }

    /** Called every client tick. */
    public static void tick(MinecraftClient client) {
        if (rockets.isEmpty()) return;
        if (client.world == null || client.player == null) { rockets.clear(); return; }
        Iterator<Rocket> it = rockets.iterator();
        while (it.hasNext()) {
            Rocket k = it.next();
            if (k.delay > 0) {
                if (--k.delay == 0) {
                    client.player.playSound(SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 0.9f + client.player.getRandom().nextFloat() * 0.3f);
                }
                continue;
            }
            k.x += k.vx; k.y += k.vy; k.z += k.vz;
            k.vy *= 0.97;
            client.particleManager.addParticle(ParticleTypes.FIREWORK, k.x, k.y, k.z,
                    (client.player.getRandom().nextDouble() - 0.5) * 0.05, -0.1, (client.player.getRandom().nextDouble() - 0.5) * 0.05);
            if (--k.life <= 0) {
                // The vanilla firework explosion particle plays its own bang/crackle sounds.
                client.world.addFireworkParticle(k.x, k.y, k.z, 0, 0, 0, k.burst);
                it.remove();
            }
        }
    }

    /** Small "ding" when a new top bid comes in. */
    public static void bidDing() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) c.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }
}
