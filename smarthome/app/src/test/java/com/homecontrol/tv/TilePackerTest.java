package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class TilePackerTest {

    private static int[] ones(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, 1);
        return a;
    }

    private static boolean[] talls(int n, int... at) {
        boolean[] t = new boolean[n];
        for (int i : at) t[i] = true;
        return t;
    }

    /** "0 1 _ 2": the cells as text; a spacer is _ , or _2 when it covers two columns. */
    private static String show(List<TilePacker.Cell> cells) {
        StringBuilder b = new StringBuilder();
        for (TilePacker.Cell c : cells) b.append(b.length() == 0 ? "" : " ").append(c.spacer() ? (c.span > 1 ? "_" + c.span : "_") : String.valueOf(c.index));
        return b.toString();
    }

    /** Where the grid view puts each cell: left to right, onto the next row when the cell does not fit the rest of this one. */
    private static int[][] place(int columns, List<TilePacker.Cell> cells) {
        int[][] at = new int[cells.size()][];
        int row = 0, col = 0;
        for (int i = 0; i < cells.size(); i++) {
            int span = cells.get(i).span;
            if (col + span > columns) {
                row++;
                col = 0;
            }
            at[i] = new int[]{row, col};
            col += span;
            if (col >= columns) {
                row++;
                col = 0;
            }
        }
        return at;
    }

    /** The reference: CSS grid auto-placement (no "dense"), done the long way on a board. Returns {row, col} per tile. */
    private static int[][] reference(int columns, int[] spans, boolean[] tall) {
        Set<Long> taken = new HashSet<>();
        int[][] at = new int[spans.length][];
        int row = 0, col = 0;
        for (int i = 0; i < spans.length; i++) {
            int w = Math.max(1, Math.min(spans[i], columns)), h = tall[i] ? 2 : 1;
            while (true) {
                if (col + w > columns) {
                    row++;
                    col = 0;
                }
                boolean fits = true;
                for (int r = row; r < row + h && fits; r++) {
                    for (int c = col; c < col + w; c++) if (taken.contains(key(r, c))) fits = false;
                }
                if (fits) break;
                col++;
            }
            for (int r = row; r < row + h; r++) for (int c = col; c < col + w; c++) taken.add(key(r, c));
            at[i] = new int[]{row, col};
            col += w;
        }
        return at;
    }

    private static long key(int r, int c) {
        return r * 1000L + c;
    }

    @Test
    public void nothing_to_lay_out_gives_nothing() {
        assertEquals("", show(TilePacker.pack(4, new int[0], new boolean[0])));
    }

    @Test
    public void plain_tiles_keep_their_order_and_get_no_spacers() {
        assertEquals("0 1 2 3 4 5 6", show(TilePacker.pack(4, ones(7), talls(7))));
    }

    @Test
    public void a_tall_tile_gets_a_spacer_in_the_cell_below_it() {
        assertEquals("0 _3 _", show(TilePacker.pack(4, ones(1), talls(1, 0))));
        // 0 is tall at column 0; 1 2 3 fill the row; the next row starts with the spacer under 0, then 4 5 6
        assertEquals("0 1 2 3 _ 4 5 6", show(TilePacker.pack(4, ones(7), talls(7, 0))));
    }

    @Test
    public void a_tall_tile_in_the_middle_and_at_the_end_of_a_row() {
        assertEquals("0 1 2 3 4 _ 5 6", show(TilePacker.pack(4, ones(7), talls(7, 1))));
        int[][] at = place(4, TilePacker.pack(4, ones(7), talls(7, 3)));
        assertEquals(0, at[3][0]);
        assertEquals(3, at[3][1]);
        assertEquals("0 1 2 3 4 5 6 _", show(TilePacker.pack(4, ones(7), talls(7, 3))));
    }

    @Test
    public void a_tall_tile_last_in_its_section_still_gets_its_row_below() {
        // columns 4, tall at column 2: the row below needs two gaps and the spacer so the columns line up
        List<TilePacker.Cell> cells = TilePacker.pack(4, ones(3), talls(3, 2));
        assertEquals("0 1 2 _ _3", show(cells));
        // the spacer row below covers columns 0-2: it ends right under the tall tile, which is at column 2
        assertEquals(1, place(4, cells)[4][0]);
        assertEquals(0, place(4, cells)[4][1]);
    }

    @Test
    public void a_wide_tall_tile_takes_two_cells_below() {
        List<TilePacker.Cell> cells = TilePacker.pack(4, new int[]{2, 1, 1, 1}, talls(4, 0));
        assertEquals("0 1 2 _2 3", show(cells));
        assertEquals(2, cells.get(0).span);
        assertTrue(cells.get(0).tall);
    }

    @Test
    public void two_tall_neighbours_leave_two_spacers() {
        assertEquals("0 1 2 3 _2 4 5", show(TilePacker.pack(4, ones(6), talls(6, 0, 1))));
    }

    @Test
    public void a_wide_tile_that_does_not_fit_beside_a_tall_one_waits_for_room_in_order() {
        // 0 tall at column 0, 1 and 2 normal, then 3 is wide and 4 normal. Row 1 has the spacer at column 0 and
        // columns 1-3 free: 3 goes at columns 1-2 and 4 after it, order kept.
        List<TilePacker.Cell> cells = TilePacker.pack(4, new int[]{1, 1, 1, 2, 1}, talls(5, 0));
        assertEquals("0 1 2 _ _ 3 4", show(cells));
        // a wide tile with only one free cell left in the row goes to the next row, leaving the cell empty
        cells = TilePacker.pack(4, new int[]{1, 1, 1, 2}, talls(4));
        assertEquals("0 1 2 _ 3", show(cells));
        assertEquals(1, place(4, cells)[4][0]);
        assertEquals(0, place(4, cells)[4][1]);
    }

    @Test
    public void a_wide_tile_skips_past_an_occupied_cell_with_gaps_that_keep_the_columns() {
        // columns 4: 0 normal+tall at col 0, 1 wide needs 2 free in row 0 -> cols 1-2, 2 wide -> doesn't fit col 3 -> row 1
        // row 1: col 0 occupied (spacer), col 1-2 free: wide 2 placed at 1-2.
        int[] spans = {1, 2, 2};
        boolean[] tall = talls(3, 0);
        assertSamePlaces(4, spans, tall);
        // a gap before an occupied cell: row 1 is [free, occupied, free, free]: wide cannot start at 0
        assertSamePlaces(4, new int[]{1, 1, 1, 1, 2}, talls(5, 1));
        assertEquals("0 1 2 3 _2 4", show(TilePacker.pack(4, new int[]{1, 1, 1, 1, 2}, talls(5, 1))));
    }

    @Test
    public void one_column_stacks_tall_tiles_with_a_spacer_below_each() {
        assertEquals("0 _ 1 2 _", show(TilePacker.pack(1, ones(3), talls(3, 0, 2))));
    }

    @Test
    public void widths_are_kept_inside_the_grid() {
        List<TilePacker.Cell> cells = TilePacker.pack(3, new int[]{5, 0, -2}, talls(3));
        assertEquals(3, cells.get(0).span);   // wider than the grid: the whole row
        assertEquals(1, cells.get(1).span);   // no width: one column
        assertEquals(1, cells.get(2).span);
        assertEquals("0 1 2", show(cells));
    }

    @Test
    public void tall_wide_as_wide_as_the_grid_fills_the_row_below() {
        assertEquals("0 _2 1", show(TilePacker.pack(2, new int[]{2, 1}, talls(2, 0))));
        assertEquals("0 _2 1", show(TilePacker.pack(2, new int[]{9, 1}, talls(2, 0))));
    }

    @Test
    public void bad_input_is_refused() {
        try {
            TilePacker.pack(0, new int[0], new boolean[0]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            TilePacker.pack(4, new int[2], new boolean[3]);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void the_last_cell_is_never_a_blank_gap() {
        for (int columns = 1; columns <= 6; columns++) {
            for (int n = 1; n <= 8; n++) {
                for (int mask = 0; mask < (1 << n); mask++) {
                    boolean[] tall = new boolean[n];
                    for (int i = 0; i < n; i++) tall[i] = (mask >> i & 1) == 1;
                    List<TilePacker.Cell> cells = TilePacker.pack(columns, ones(n), tall);
                    if (cells.get(cells.size() - 1).spacer()) {
                        // a trailing spacer is only there under a tall tile
                        int[][] at = place(columns, cells);
                        int[] last = at[cells.size() - 1];
                        last = new int[]{last[0], last[1] + cells.get(cells.size() - 1).span - 1};   // its last column
                        boolean covered = false;
                        for (int i = 0; i < cells.size(); i++) {
                            TilePacker.Cell c = cells.get(i);
                            if (c.spacer() || !c.tall) continue;
                            if (at[i][0] + 1 == last[0] && last[1] >= at[i][1] && last[1] < at[i][1] + c.span) covered = true;
                        }
                        assertTrue("columns " + columns + " n " + n + " mask " + mask, covered);
                    }
                }
            }
        }
    }

    private static void assertSamePlaces(int columns, int[] spans, boolean[] tall) {
        List<TilePacker.Cell> cells = TilePacker.pack(columns, spans, tall);
        int[][] got = place(columns, cells);
        int[][] want = reference(columns, spans, tall);
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).spacer()) continue;
            int tile = cells.get(i).index;
            seen.add(tile);
            assertEquals("row of tile " + tile + " columns " + columns + " spans " + java.util.Arrays.toString(spans)
                    + " tall " + java.util.Arrays.toString(tall) + " cells " + show(cells), want[tile][0], got[i][0]);
            assertEquals("column of tile " + tile + " columns " + columns + " spans " + java.util.Arrays.toString(spans)
                    + " tall " + java.util.Arrays.toString(tall) + " cells " + show(cells), want[tile][1], got[i][1]);
        }
        for (int i = 0; i < spans.length; i++) assertEquals("order", i, (int) seen.get(i));   // each tile once, in order
        assertEquals(spans.length, seen.size());
    }

    /** Every combination of up to 7 tiles with widths 1-2 and either height, in grids of 1 to 5 columns. */
    @Test
    public void matches_css_grid_placement_for_every_small_combination() {
        for (int columns = 1; columns <= 5; columns++) {
            for (int n = 0; n <= 7; n++) {
                int combos = 1;
                for (int i = 0; i < n; i++) combos *= 4;
                for (int code = 0; code < combos; code++) {
                    int[] spans = new int[n];
                    boolean[] tall = new boolean[n];
                    int c = code;
                    for (int i = 0; i < n; i++) {
                        spans[i] = 1 + (c & 1);
                        tall[i] = (c >> 1 & 1) == 1;
                        c >>= 2;
                    }
                    assertSamePlaces(columns, spans, tall);
                }
            }
        }
    }

    @Test
    public void matches_css_grid_placement_for_random_long_lists() {
        Random random = new Random(42);
        for (int round = 0; round < 3000; round++) {
            int columns = 1 + random.nextInt(8), n = random.nextInt(40);
            int[] spans = new int[n];
            boolean[] tall = new boolean[n];
            for (int i = 0; i < n; i++) {
                spans[i] = random.nextInt(10) < 7 ? 1 : 1 + random.nextInt(columns + 1);   // mostly 1, sometimes wider than the grid
                tall[i] = random.nextInt(10) < 3;
            }
            assertSamePlaces(columns, spans, tall);
        }
    }

    @Test
    public void no_tile_overlaps_another_and_no_spacer_sits_on_a_tile() {
        Random random = new Random(7);
        for (int round = 0; round < 3000; round++) {
            int columns = 1 + random.nextInt(6), n = random.nextInt(30);
            int[] spans = new int[n];
            boolean[] tall = new boolean[n];
            for (int i = 0; i < n; i++) {
                spans[i] = 1 + random.nextInt(2);
                tall[i] = random.nextBoolean();
            }
            List<TilePacker.Cell> cells = TilePacker.pack(columns, spans, tall);
            int[][] at = place(columns, cells);
            Set<Long> tiles = new HashSet<>();
            for (int i = 0; i < cells.size(); i++) {
                TilePacker.Cell c = cells.get(i);
                assertTrue("a cell straddles the edge", at[i][1] + c.span <= columns);
                if (c.spacer()) continue;
                int h = c.tall ? 2 : 1;
                for (int r = at[i][0]; r < at[i][0] + h; r++) {
                    for (int col = at[i][1]; col < at[i][1] + c.span; col++) {
                        assertTrue("two tiles on " + r + "," + col, tiles.add(key(r, col)));
                    }
                }
            }
            // a spacer may only cover a cell that a tile above reaches into, or empty space; never a tile's first row
            for (int i = 0; i < cells.size(); i++) {
                if (!cells.get(i).spacer()) continue;
                for (int col = at[i][1]; col < at[i][1] + cells.get(i).span; col++) {
                    for (int j = 0; j < cells.size(); j++) {
                        if (cells.get(j).spacer() || at[j][0] != at[i][0]) continue;
                        boolean overlaps = col >= at[j][1] && col < at[j][1] + cells.get(j).span;
                        assertFalse("a spacer on top of tile " + cells.get(j).index, overlaps);
                    }
                }
            }
            // every row except the last is full: the cells span exactly `columns`
            int[] filled = new int[cells.isEmpty() ? 1 : at[cells.size() - 1][0] + 1];
            for (int i = 0; i < cells.size(); i++) filled[at[i][0]] += cells.get(i).span;
            for (int r = 0; r < filled.length - 1; r++) assertEquals("row " + r + " is not full", columns, filled[r]);
        }
    }

    @Test
    public void a_long_list_is_laid_out_quickly() {
        int n = 20000;
        boolean[] tall = new boolean[n];
        for (int i = 0; i < n; i += 3) tall[i] = true;
        long t0 = System.nanoTime();
        List<TilePacker.Cell> cells = TilePacker.pack(4, ones(n), tall);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
        int items = 0;
        for (TilePacker.Cell c : cells) if (!c.spacer()) items++;
        assertEquals(n, items);
        assertFalse(cells.isEmpty());
    }
}
