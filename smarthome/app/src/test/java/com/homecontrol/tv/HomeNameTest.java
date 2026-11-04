package com.homecontrol.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

public class HomeNameTest {

    /** A query for one name and type, as a phone or a Mac sends it. */
    private static byte[] query(String name, int type, int cls) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int[] header = {0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        for (int x : header) b.write(x);
        for (String l : name.split("\\.")) {
            b.write(l.length());
            b.write(l.getBytes(), 0, l.length());
        }
        b.write(0);
        b.write(type >> 8); b.write(type); b.write(cls >> 8); b.write(cls);
        return b.toByteArray();
    }

    @Test
    public void answers_only_address_questions_for_its_own_name() {
        byte[] a = query("home.local", 1, 1);
        assertEquals(1, HomeName.asks(a, a.length));
        byte[] upper = query("HOME.local", 1, 1);
        assertEquals(1, HomeName.asks(upper, upper.length));
        byte[] direct = query("home.local", 255, 0x8001);
        assertTrue((HomeName.asks(direct, direct.length) & 0x8000) != 0);
        byte[] other = query("door-cam.local", 1, 1);
        assertEquals(-1, HomeName.asks(other, other.length));
        byte[] aaaa = query("home.local", 28, 1);   // answered too, to say there's no IPv6 address
        assertEquals(1, HomeName.asks(aaaa, aaaa.length));
    }

    @Test
    public void ignores_responses_and_broken_messages() {
        byte[] r = HomeName.answer(0, new byte[]{(byte) 192, (byte) 168, 1, (byte) 248});
        assertEquals(-1, HomeName.asks(r, r.length));
        byte[] q = query("home.local", 1, 1);
        assertEquals(-1, HomeName.asks(q, q.length - 6));
        byte[] loop = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, (byte) 0xc0, 12, 0, 1, 0, 1};
        assertEquals(-1, HomeName.asks(loop, loop.length));
    }

    @Test
    public void answer_carries_the_address() {
        byte[] ip = {(byte) 192, (byte) 168, 1, (byte) 248};
        byte[] r = HomeName.answer(0x1234, ip);
        assertEquals(0x12, r[0]);
        assertEquals(0x34, r[1]);
        byte[] got = new byte[4];
        System.arraycopy(r, 34, got, 0, 4);   // after the header, home.local and the record's fields
        assertArrayEquals(ip, got);
        byte[] nsec = new byte[5];
        System.arraycopy(r, r.length - 5, nsec, 0, 5);
        assertArrayEquals(new byte[]{(byte) 0xc0, 12, 0, 1, 0x40}, nsec);   // only an A record
    }
}
