package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeSizeTest {

    private static Home home() {
        return Home.fromJson("{\"rooms\":[],\"devices\":[],\"cameras\":[],\"scenes\":[]}");
    }

    @Test
    public void tiles_start_normal_size() {
        Home h = home();
        assertFalse(h.isWide("device", "d1"));
        assertFalse(h.isTall("device", "d1"));
        assertTrue(h.wide.isEmpty());
        assertTrue(h.tall.isEmpty());
    }

    @Test
    public void width_and_height_are_separate() {
        Home h = home();
        h.setWide("scene", "s1", true);
        assertTrue(h.isWide("scene", "s1"));
        assertFalse(h.isTall("scene", "s1"));
        h.setTall("scene", "s1", true);
        assertTrue(h.isTall("scene", "s1"));
        h.setWide("scene", "s1", false);
        assertFalse(h.isWide("scene", "s1"));
        assertTrue(h.isTall("scene", "s1"));   // turning one off leaves the other
        h.setTall("scene", "s1", false);
        assertFalse(h.isTall("scene", "s1"));
    }

    @Test
    public void setting_twice_or_unsetting_what_was_never_set_does_nothing_odd() {
        Home h = home();
        h.setWide("camera", "c", true);
        h.setWide("camera", "c", true);
        assertEquals(1, h.wide.size());
        h.setWide("camera", "other", false);
        assertEquals(1, h.wide.size());
        h.setTall("camera", "c", false);
        assertTrue(h.tall.isEmpty());
    }

    @Test
    public void the_same_id_under_another_kind_is_another_tile() {
        Home h = home();
        h.setWide("device", "x", true);
        assertFalse(h.isWide("scene", "x"));
        assertFalse(h.isWide("camera", "x"));
        assertFalse(h.isWide("automation", "x"));
    }

    @Test
    public void sensors_count_as_devices_and_schedules_as_automations() {
        Home h = home();
        h.setWide("sensor", "t1", true);
        assertTrue(h.isWide("device", "t1"));
        h.setTall("device", "t2", true);
        assertTrue(h.isTall("sensor", "t2"));
        h.setWide("schedule", "a1", true);
        assertTrue(h.isWide("automation", "a1"));
        assertEquals("device", Home.wideKind("sensor"));
        assertEquals("automation", Home.wideKind("schedule"));
        assertEquals("remote", Home.wideKind("remote"));
    }

    @Test
    public void the_remote_tile_can_be_resized_too() {
        Home h = home();
        h.setWide("remote", "tv", true);
        h.setTall("remote", "tv", true);
        assertTrue(h.isWide("remote", "tv"));
        assertTrue(h.isTall("remote", "tv"));
    }

    @Test
    public void sizes_survive_saving_and_loading() {
        Home h = home();
        h.setWide("device", "d1", true);
        h.setTall("device", "d1", true);
        h.setWide("scene", "s1", true);
        h.setTall("camera", "c1", true);
        h.setWide("remote", "tv", true);
        Home back = Home.fromJson(h.toJson());
        assertTrue(back.isWide("device", "d1"));
        assertTrue(back.isTall("device", "d1"));
        assertTrue(back.isWide("scene", "s1"));
        assertFalse(back.isTall("scene", "s1"));
        assertTrue(back.isTall("camera", "c1"));
        assertFalse(back.isWide("camera", "c1"));
        assertTrue(back.isWide("remote", "tv"));
        assertEquals(h.wide, back.wide);
        assertEquals(h.tall, back.tall);
    }

    @Test
    public void saved_before_sizes_existed_loads_with_everything_normal() {
        Home h = Home.fromJson("{\"version\":2,\"rooms\":[],\"devices\":[{\"id\":\"d\",\"host\":\"10.0.0.2\"}]}");
        assertTrue(h.wide.isEmpty());
        assertTrue(h.tall.isEmpty());
        assertFalse(h.isWide("device", "d"));
    }

    @Test
    public void odd_saved_values_do_not_break_loading() {
        Home h = Home.fromJson("{\"rooms\":[],\"wide\":\"nope\",\"tall\":[1,null,\"scene:s\",{}]}");
        assertTrue(h.wide.isEmpty());
        assertTrue(h.isTall("scene", "s"));
    }

    @Test
    public void sizes_do_not_touch_the_section_order() {
        Home h = home();
        h.sections.clear();
        h.sections.add("scenes");
        h.sections.add("plugs");
        h.setWide("scene", "s", true);
        Home back = Home.fromJson(h.toJson());
        assertTrue(back.sections.indexOf("scenes") < back.sections.indexOf("plugs"));   // the saved order is kept
        assertEquals(back.sections, Home.fromJson(back.toJson()).sections);             // and is stable from then on
        assertTrue(back.isWide("scene", "s"));
    }

    @Test
    public void ids_with_odd_characters_are_kept_as_they_are() {
        Home h = home();
        String id = "a:b \"quoted\" ü";
        h.setTall("device", id, true);
        assertTrue(Home.fromJson(h.toJson()).isTall("device", id));
    }
}
