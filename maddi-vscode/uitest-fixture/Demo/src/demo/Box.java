package demo;

// incremental re-analysis, "changed": the test edits peek, so that it modifies its cell
public class Box {
    public int peek(Cell cell) {
        return cell.get();
    }
}
