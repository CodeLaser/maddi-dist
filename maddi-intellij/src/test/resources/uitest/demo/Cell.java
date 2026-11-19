package demo;

// incremental re-analysis, "before": Box depends on Cell, Cell on nothing that changes
public class Cell {
    private int value;

    public int get() {
        return value;
    }

    public void set(int value) {
        this.value = value;
    }
}
