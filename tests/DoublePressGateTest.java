import com.sunny.glassesbridge.DoublePressGate;

public class DoublePressGateTest {
    static void check(boolean value) { if (!value) throw new AssertionError("Double-press regression"); }
    public static void main(String[] args) {
        DoublePressGate gate = new DoublePressGate(650);
        check(!gate.shortPress(1000));
        check(gate.shortPress(1300));
        check(!gate.shortPress(1500)); // A third press must not reuse the second.
        check(!gate.shortPress(2300)); // Expired first press.
        check(gate.shortPress(2950)); // Exact boundary.
        check(!gate.shortPress(4000));
        gate.reset(); // Disarm, background or non-short gesture cancels a pending pair.
        check(!gate.shortPress(4200));
        check(gate.shortPress(4500));
        check(!gate.shortPress(6000));
        check(!gate.shortPress(5000)); // Clock discontinuity cannot count as a double.
        check(gate.shortPress(5200));
        System.out.println("PASS: double-press pairing, expiry, reset, and repeat suppression");
    }
}
