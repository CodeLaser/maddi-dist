package demo;

import java.util.List;

// a near miss: a container but for one of its parameters, which copyTo modifies. The analyzer reports a near miss
// on a surface of at least 7 parameters, of which exactly 1 is modified.
public class Names {
    private final String first;
    private final String last;

    public Names(String first, String last) {
        this.first = first;
        this.last = last;
    }

    public String greet(String greeting) {
        return greeting + " " + first;
    }

    public boolean startsWith(String prefix) {
        return first.startsWith(prefix);
    }

    public String join(String separator, String end) {
        return first + separator + last + end;
    }

    public boolean sameLast(String other) {
        return last.equals(other);
    }

    public void copyTo(List<String> target) {
        target.add(first);
    }
}
