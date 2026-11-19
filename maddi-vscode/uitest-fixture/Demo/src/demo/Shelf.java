package demo;

// incremental re-analysis, "rewired": Shelf uses Cell and Box, so an edit of Box re-points it at the new Box
public class Shelf {
    public int read(Box box, Cell cell) {
        return box.peek(cell);
    }

    public int direct(Cell cell) {
        return cell.get();
    }
}
