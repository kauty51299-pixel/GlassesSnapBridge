import com.sunny.glassesbridge.ConnectionFailure;

public class ConnectionFailureTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Connection error regression");
    }

    public static void main(String[] args) {
        ConnectionFailure pair = new ConnectionFailure();
        pair.add("SESSION_ENDED_BY_DEVICE", "Session ended by device");
        pair.add("NO_ELIGIBLE_DEVICE", "No eligible device available");
        check(pair.details().contains("SESSION_ENDED_BY_DEVICE"));
        check(pair.details().contains("NO_ELIGIBLE_DEVICE"));
        check(pair.recoveryMessage().contains("not made the glasses available"));

        ConnectionFailure runtime = new ConnectionFailure();
        runtime.add("SESSION_STOPPED", "Stopped");
        runtime.add("DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED", "Your glasses need an update");
        runtime.add("SESSION_ENDED_BY_DEVICE", "Session ended by device");
        check(runtime.recoveryMessage().contains("Update glasses runtime"));
        check(runtime.details().contains("DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED"));

        ConnectionFailure firmware = new ConnectionFailure();
        firmware.add("DEVICE_UPDATE_REQUIRED", "Firmware too old");
        check(firmware.recoveryMessage().contains("Update glasses firmware"));

        // A duplicate event should not hide another reason or grow the report.
        pair.add("SESSION_ENDED_BY_DEVICE", "Session ended by device again");
        check(pair.details().split("\n").length == 2);
        check(pair.details().contains("NO_ELIGIBLE_DEVICE"));

        ConnectionFailure independent = new ConnectionFailure();
        independent.add("UNKNOWN", "Unexpected failure");
        check(!independent.details().contains("NO_ELIGIBLE_DEVICE"));
        check(independent.recoveryMessage().contains("Copy connection details"));
        System.out.println("PASS: SDK secondary-error retention and recovery routing");
    }
}
