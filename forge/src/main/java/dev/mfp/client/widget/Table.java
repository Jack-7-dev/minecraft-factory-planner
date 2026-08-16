package dev.mfp.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A column-per-field, row-per-line table with variable row heights.
 *
 * <p>Rows vary in height because a production line's ingredient list does: a chemical reactor with
 * six inputs needs three rows of icons and a smelter needs one. Fixing the height would mean either
 * wasting two thirds of the table on whitespace or hiding ingredients, and a hidden ingredient in a
 * planner is a wrong answer.
 *
 * <p>The header is drawn by {@link #renderHeader}, separately from the body, so the screen can pin
 * it above a {@link ScrollPanel} while the rows scroll underneath.
 */
public final class Table extends MfpWidget {

    public static final int HEADER_HEIGHT = 12;

    private static final int PADDING = 3;
    private static final int MIN_ROW_HEIGHT = 20;

    /**
     * One cell's contents. Given the box it must fit in, it says how tall it needs to be, draws
     * itself and answers for tooltips.
     */
    public interface Cell {

        int preferredHeight(int width);

        void render(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY);

        default List<Component> tooltip(int x, int y, int width, int height, int mouseX, int mouseY) {
            return List.of();
        }

        /**
         * A click landing inside this cell. Returning true consumes it, so the row's own handler
         * does not also fire — which is what lets a "pin" button live inside a clickable row.
         */
        default boolean mouseClicked(int x, int y, int width, int height,
                                     double mouseX, double mouseY, int button) {
            return false;
        }
    }

    /**
     * @param weight share of the available width, relative to the other columns
     * @param tooltip explanation shown when hovering the heading, or null
     */
    public record Column(String title, float weight, String tooltip) {

        public Column(String title, float weight) {
            this(title, weight, null);
        }
    }

    /**
     * @param onClick what a click anywhere in the row does, or null for an inert row
     * @param marker  colour of a bar down the row's left edge, or 0 for none
     */
    private record Row(List<Cell> cells, int background, IntConsumer onClick, int marker) {}

    private final List<Column> columns;
    private final List<Row> rows = new ArrayList<>();

    private int[] columnWidths;
    private int[] rowHeights;
    private int laidOutFor = -1;
    private int totalHeight;

    private int bandTop = Integer.MIN_VALUE;
    private int bandBottom = Integer.MAX_VALUE;

    private int draggedRow = -1;
    private int dropIndex = -1;

    public Table(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    /**
     * @param background a tint drawn behind the whole row, or 0 for the default striping
     */
    public Table addRow(List<Cell> cells, int background) {
        return addRow(cells, background, null);
    }

    /**
     * @param onClick receives the mouse button; null for a row that does nothing when clicked
     */
    public Table addRow(List<Cell> cells, int background, IntConsumer onClick) {
        return addRow(cells, background, onClick, 0);
    }

    /**
     * @param marker a colour for a two-pixel bar down the row's left edge, or 0 for none
     *
     * <p>A bar rather than a tint because the row already uses its background for the thing that
     * must be unmissable — a line that cannot run — and a second tint would either compete with it
     * or be hidden by it.
     */
    public Table addRow(List<Cell> cells, int background, IntConsumer onClick, int marker) {
        rows.add(new Row(List.copyOf(cells), background, onClick, marker));
        laidOutFor = -1;
        return this;
    }

    public int rowCount() {
        return rows.size();
    }

    /**
     * Work out column widths and row heights for a given width.
     *
     * <p>Must be called before {@link #contentHeight()}, because the height of a wrapping cell is
     * a function of how wide it ended up.
     */
    public Table layout(int forWidth) {
        if (laidOutFor == forWidth) {
            return this;
        }
        laidOutFor = forWidth;

        float totalWeight = 0;
        for (Column column : columns) {
            totalWeight += column.weight();
        }
        columnWidths = new int[columns.size()];
        int used = 0;
        for (int i = 0; i < columns.size(); i++) {
            columnWidths[i] = (int) (forWidth * columns.get(i).weight() / totalWeight);
            used += columnWidths[i];
        }
        // Rounding leftovers go to the widest column rather than being lost, so the last column's
        // right edge lines up with the table's.
        if (!columns.isEmpty()) {
            columnWidths[widestColumn()] += forWidth - used;
        }

        rowHeights = new int[rows.size()];
        totalHeight = 0;
        for (int r = 0; r < rows.size(); r++) {
            int tallest = MIN_ROW_HEIGHT;
            List<Cell> cells = rows.get(r).cells();
            for (int c = 0; c < cells.size() && c < columnWidths.length; c++) {
                Cell cell = cells.get(c);
                if (cell != null) {
                    tallest = Math.max(tallest, cell.preferredHeight(columnWidths[c] - 2 * PADDING) + 2 * PADDING);
                }
            }
            rowHeights[r] = tallest;
            totalHeight += tallest;
        }
        return this;
    }

    public int contentHeight() {
        return totalHeight;
    }

    public void renderHeader(GuiGraphics graphics, int headerX, int headerY, int forWidth) {
        layout(forWidth);
        graphics.fill(headerX, headerY, headerX + forWidth, headerY + HEADER_HEIGHT, 0x50000000);
        int cursor = headerX;
        for (int i = 0; i < columns.size(); i++) {
            graphics.drawString(font(), fit(columns.get(i).title(), columnWidths[i] - 2 * PADDING),
                    cursor + PADDING, headerY + 2, Theme.TEXT_HEADER, false);
            cursor += columnWidths[i];
            if (i < columns.size() - 1) {
                graphics.fill(cursor, headerY, cursor + 1, headerY + HEADER_HEIGHT, Theme.SEPARATOR);
            }
        }
        graphics.fill(headerX, headerY + HEADER_HEIGHT - 1, headerX + forWidth, headerY + HEADER_HEIGHT,
                Theme.BORDER);
    }

    /** The column headings' own tooltips; asked for by the screen, since it owns the header's row. */
    public List<Component> headerTooltip(int headerX, int headerY, int forWidth, int mouseX, int mouseY) {
        if (mouseY < headerY || mouseY >= headerY + HEADER_HEIGHT || columnWidths == null) {
            return List.of();
        }
        int cursor = headerX;
        for (int i = 0; i < columns.size(); i++) {
            if (mouseX >= cursor && mouseX < cursor + columnWidths[i]) {
                String tooltip = columns.get(i).tooltip();
                return tooltip == null ? List.of() : List.of(Component.literal(tooltip));
            }
            cursor += columnWidths[i];
        }
        return List.of();
    }

    /**
     * A drag in progress: which row was picked up, and where it would land.
     *
     * <p>Set by the screen every frame rather than held here, because the table is rebuilt whenever
     * anything about the plan changes and state living in it would not survive the gesture. The
     * table only draws what it is told.
     *
     * @param row   the row being dragged, or -1 for none
     * @param index the insertion position, 0..{@link #rowCount()}, or -1 for none
     */
    public Table drag(int row, int index) {
        this.draggedRow = row;
        this.dropIndex = index;
        return this;
    }

    /**
     * Where a row dropped at {@code mouseY} would be inserted, from 0 to {@link #rowCount()}.
     *
     * <p>Boundaries rather than rows: the answer for "just below row 3" and "just above row 4" is
     * the same position, and using row midpoints is what makes the indicator follow the cursor
     * without a dead zone between every pair of rows.
     */
    public int insertionIndexAt(double mouseY) {
        if (rowHeights == null) {
            return -1;
        }
        int rowY = y;
        for (int r = 0; r < rows.size(); r++) {
            if (mouseY < rowY + rowHeights[r] / 2.0) {
                return r;
            }
            rowY += rowHeights[r];
        }
        return rows.size();
    }

    /** The screen y of the boundary above row {@code index}; the table's bottom edge at rowCount. */
    public int boundaryY(int index) {
        if (rowHeights == null) {
            return y;
        }
        int rowY = y;
        for (int r = 0; r < Math.min(index, rows.size()); r++) {
            rowY += rowHeights[r];
        }
        return rowY;
    }

    /**
     * Only rows overlapping this screen band are drawn.
     *
     * <p>Row heights still have to be walked to know where each row sits — they vary (see the class
     * comment), so there is no arithmetic shortcut to the first visible one — but walking an int
     * array is nothing beside rendering a row of item stacks.
     */
    @Override
    public void visibleBand(int top, int bottom) {
        this.bandTop = top;
        this.bandBottom = bottom;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layout(width);
        int rowY = y;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            int rowHeight = rowHeights[r];

            if (rowY + rowHeight <= bandTop || rowY >= bandBottom) {
                rowY += rowHeight;
                continue;
            }

            if (row.background() != 0) {
                graphics.fill(x, rowY, x + width, rowY + rowHeight, row.background());
            } else if (r % 2 == 1) {
                graphics.fill(x, rowY, x + width, rowY + rowHeight, Theme.ROW_ODD);
            }
            if (mouseY >= rowY && mouseY < rowY + rowHeight && mouseX >= x && mouseX < x + width) {
                graphics.fill(x, rowY, x + width, rowY + rowHeight, Theme.ROW_HOVER);
            }
            if (r == draggedRow) {
                // The row you are holding, marked so the indicator's meaning is unambiguous: it is
                // where *this* row goes, not where some row goes.
                graphics.fill(x, rowY, x + width, rowY + rowHeight, Theme.ROW_SELECTED);
            }
            if (row.marker() != 0) {
                graphics.fill(x, rowY, x + 2, rowY + rowHeight - 1, row.marker());
            }

            int cursor = x;
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = c < row.cells().size() ? row.cells().get(c) : null;
                if (cell != null) {
                    cell.render(graphics, cursor + PADDING, rowY + PADDING,
                            columnWidths[c] - 2 * PADDING, rowHeight - 2 * PADDING, mouseX, mouseY);
                }
                cursor += columnWidths[c];
                if (c < columns.size() - 1) {
                    graphics.fill(cursor, rowY, cursor + 1, rowY + rowHeight, Theme.SEPARATOR);
                }
            }
            graphics.fill(x, rowY + rowHeight - 1, x + width, rowY + rowHeight, Theme.SEPARATOR);
            rowY += rowHeight;
        }

        // Last, so it lies over the rows on both sides of the boundary rather than under one of them.
        if (dropIndex >= 0) {
            int lineY = boundaryY(dropIndex);
            graphics.fill(x, lineY - 1, x + width, lineY + 1, Theme.PINNED);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (rowHeights == null || columnWidths == null) {
            return false;
        }
        int rowY = y;
        for (int r = 0; r < rows.size(); r++) {
            int rowHeight = rowHeights[r];
            if (mouseY >= rowY && mouseY < rowY + rowHeight && mouseX >= x && mouseX < x + width) {
                Row row = rows.get(r);
                int cursor = x;
                for (int c = 0; c < columns.size(); c++) {
                    if (mouseX >= cursor && mouseX < cursor + columnWidths[c]) {
                        Cell cell = c < row.cells().size() ? row.cells().get(c) : null;
                        if (cell != null && cell.mouseClicked(cursor + PADDING, rowY + PADDING,
                                columnWidths[c] - 2 * PADDING, rowHeight - 2 * PADDING,
                                mouseX, mouseY, button)) {
                            return true;
                        }
                        break;
                    }
                    cursor += columnWidths[c];
                }
                if (row.onClick() != null) {
                    row.onClick().accept(button);
                    return true;
                }
                return false;
            }
            rowY += rowHeight;
        }
        return false;
    }

    @Override
    public List<Component> tooltip(int mouseX, int mouseY) {
        if (rowHeights == null || columnWidths == null) {
            return List.of();
        }
        int rowY = y;
        for (int r = 0; r < rows.size(); r++) {
            int rowHeight = rowHeights[r];
            if (mouseY >= rowY && mouseY < rowY + rowHeight) {
                int cursor = x;
                for (int c = 0; c < columns.size(); c++) {
                    if (mouseX >= cursor && mouseX < cursor + columnWidths[c]) {
                        Cell cell = c < rows.get(r).cells().size() ? rows.get(r).cells().get(c) : null;
                        return cell == null ? List.of() : cell.tooltip(cursor + PADDING, rowY + PADDING,
                                columnWidths[c] - 2 * PADDING, rowHeight - 2 * PADDING, mouseX, mouseY);
                    }
                    cursor += columnWidths[c];
                }
                return List.of();
            }
            rowY += rowHeight;
        }
        return List.of();
    }

    private int widestColumn() {
        int widest = 0;
        for (int i = 1; i < columnWidths.length; i++) {
            if (columnWidths[i] > columnWidths[widest]) {
                widest = i;
            }
        }
        return widest;
    }
}
