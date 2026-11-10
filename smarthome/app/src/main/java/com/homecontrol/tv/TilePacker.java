package com.homecontrol.tv;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays tiles out in a grid where a tile can be 2 wide and 2 tall, the same way on the TV and on the phone page
 * (CSS grid auto-placement without "dense": every tile goes in the first place after the previous one where it fits,
 * so the order never changes). A double-height tile takes the cell below it as well. The TV's grid only knows
 * rows, so this lists the cells row by row, with a spacer cell (index -1) wherever a tile above reaches down
 * or an empty cell has to keep the columns in line. Plain Java, tested on the JVM.
 */
final class TilePacker {

    /** One cell of the list: a tile (index into the input) or a spacer (-1). */
    static final class Cell {
        final int index, span;
        final boolean tall;

        Cell(int index, int span, boolean tall) {
            this.index = index;
            this.span = span;
            this.tall = tall;
        }

        boolean spacer() {
            return index < 0;
        }
    }

    private TilePacker() {
    }

    /**
     * @param columns how many columns the grid has (at least 1)
     * @param spans   each tile's width in columns (clamped to 1..columns)
     * @param tall    whether each tile is double height
     * @return the cells in reading order: row after row, left to right
     */
    static List<Cell> pack(int columns, int[] spans, boolean[] tall) {
        if (columns < 1) throw new IllegalArgumentException("columns");
        if (spans.length != tall.length) throw new IllegalArgumentException("spans and tall differ in length");
        List<Cell> out = new ArrayList<>();
        boolean[] occupied = new boolean[columns];   // cells of this row that a tile above reaches into
        int next = 0;
        while (next < spans.length || any(occupied)) {
            List<Cell> row = new ArrayList<>();
            boolean[] below = new boolean[columns];
            int col = 0, gap = 0, lastUsed = -1;   // gap: empty cells waiting to be written as one spacer
            while (col < columns) {
                int w = next < spans.length ? Math.max(1, Math.min(spans[next], columns)) : 0;
                if (!occupied[col] && w > 0 && col + w <= columns && free(occupied, col, w)) {
                    if (gap > 0) row.add(new Cell(-1, gap, false));
                    gap = 0;
                    row.add(new Cell(next, w, tall[next]));
                    if (tall[next]) for (int c = col; c < col + w; c++) below[c] = true;
                    col += w;
                    next++;
                    lastUsed = col;
                } else {
                    if (occupied[col]) lastUsed = col + 1;
                    gap++;
                    col++;
                }
            }
            // The rest of the row is empty. Without a row after it, the cells past the last tile or reach-through
            // need not be there; otherwise they are written, so the next cell does not slide up into them.
            boolean more = next < spans.length || any(below);
            int keep = more ? columns : lastUsed;
            int used = 0;
            for (Cell c : row) used += c.span;
            if (gap > 0 && keep - used > 0) row.add(new Cell(-1, Math.min(gap, keep - used), false));
            out.addAll(row);
            occupied = below;
        }
        return out;
    }

    private static boolean any(boolean[] a) {
        return last(a) >= 0;
    }

    private static int last(boolean[] a) {
        for (int i = a.length - 1; i >= 0; i--) if (a[i]) return i;
        return -1;
    }

    private static boolean free(boolean[] occupied, int from, int width) {
        for (int c = from; c < from + width; c++) if (occupied[c]) return false;
        return true;
    }
}
