package com.ledsync;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Sends health, dimension and time to the LED Studio app (same phone) every 5 ticks. */
public final class LedSyncClient {
    private static DatagramSocket socket;
    private static InetAddress addr;
    private static int tick;

    public static void init() {
        try { socket = new DatagramSocket(); addr = InetAddress.getByName("127.0.0.1"); }
        catch (Throwable t) { return; }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tick % 5 != 0 || client.player == null || client.level == null) return;
            try {
                long time = -1;
                for (String name : new String[] {"getDayTime", "getOverworldClockTime", "getDefaultClockTime"}) {
                    try { time = ((Number) client.level.getClass().getMethod(name).invoke(client.level)).longValue(); break; }
                    catch (Throwable ignored) { }
                }
                String msg = "dim=" + client.level.dimension() + ";t=" + time + ";hp=" + client.player.getHealth();
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(b, b.length, addr, 4210));
            } catch (Throwable ignored) { }
        });
    }
}
