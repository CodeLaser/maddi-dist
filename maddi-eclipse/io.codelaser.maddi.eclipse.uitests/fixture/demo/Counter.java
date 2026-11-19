package demo;

// mutable: increment() modifies the field
public class Counter {
    private int count;

    public void increment() {
        count++;
    }

    public int get() {
        return count;
    }
}
