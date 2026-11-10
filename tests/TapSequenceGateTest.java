import com.sunny.glassesbridge.TapSequenceGate;
public class TapSequenceGateTest {
    static void check(boolean value) { if (!value) throw new AssertionError("Multi-tap regression"); }
    public static void main(String[] args) {
        TapSequenceGate gate = new TapSequenceGate(650, 3);
        check(!gate.tap(1000,"touch"));check(!gate.tap(1250,"touch"));check(gate.tap(1500,"touch"));
        check(!gate.tap(1600,"touch")); // The last event is not reused.
        gate.setRequired(4);
        check(!gate.tap(2000,"touch"));check(!gate.tap(2250,"touch"));check(!gate.tap(2500,"touch"));check(gate.tap(2750,"touch"));
        check(!gate.tap(4000,"touch"));check(!gate.tap(4200,"button"));check(gate.count()==1); // Controls never mix.
        check(!gate.tap(5000,"button"));check(gate.count()==1); // Long gaps expire a sequence.
        gate.reset();check(gate.count()==0);check(!gate.tap(5100,"button"));
        gate.setRequired(3);check(!gate.tap(6000,"touch"));check(!gate.tap(5900,"touch"));check(gate.count()==1);
        System.out.println("PASS: triple/quadruple tap thresholds, source separation, expiry and reset");
    }
}
