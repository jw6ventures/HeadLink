// P7.3's multi-SIM signal rules on a desktop JVM: which subscription leads the header,
// how the others become dot rows, and the network type beside the bars. SimSignal is pure
// Kotlin; the per-subscription TelephonyCallbacks that feed it need a phone, and a real
// second subscription has only been reasoned about, not seen.
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.jw6ventures.headlink.SignalRows;
import com.jw6ventures.headlink.SimSignal;
import com.jw6ventures.headlink.SubscriptionSignal;

public final class SimSignalTest {
    private static int passed;
    private static final SimSignal RULES = SimSignal.INSTANCE;

    public static void main(String[] args) {
        noSimIsADash();
        theDefaultDataSubscriptionLeads();
        withoutADefaultTheLowestSlotLeads();
        outOfServiceIsEmptyBarsOrAHollowRow();
        atMostThreeRowsInSlotOrder();
        levelsAreClamped();
        networkLabels();
        System.out.println("jni-host-test: ok " + passed + " sim signal checks");
    }

    private static SubscriptionSignal sub(int id, int slot, int level, boolean inService) {
        return new SubscriptionSignal(id, slot, level, inService);
    }

    private static void noSimIsADash() {
        SignalRows rows = RULES.arrange(new ArrayList<>(), -1);
        check(rows.getPrimary() == -1 && rows.getSecondary().isEmpty() && rows.getCount() == 0, "no subscription draws a dash and no rows");
        rows = RULES.arrange(List.of(sub(1, 0, -1, true)), 1);
        check(rows.getPrimary() == -1, "a SIM not read yet is a dash too, not empty bars");
    }

    private static void theDefaultDataSubscriptionLeads() {
        SignalRows rows = RULES.arrange(List.of(sub(1, 0, 2, true), sub(2, 1, 4, true)), 2);
        check(rows.getPrimary() == 4, "the default data SIM's bars, not slot 0's: " + rows);
        check(rows.getSecondary().equals(List.of(2)), "the other SIM is the one dot row");
        check(rows.getCount() == 2, "two subscriptions counted");
    }

    private static void withoutADefaultTheLowestSlotLeads() {
        // Mobile data off on a dual-SIM phone reports no default data subscription.
        SignalRows rows = RULES.arrange(List.of(sub(7, 1, 1, true), sub(3, 0, 3, true)), -1);
        check(rows.getPrimary() == 3 && rows.getSecondary().equals(List.of(1)), "slot 0 leads when there is no default: " + rows);
        // An eSIM with no slot sorts after the physical SIMs.
        rows = RULES.arrange(List.of(sub(9, Integer.MAX_VALUE, 4, true), sub(4, 0, 1, true)), -1);
        check(rows.getPrimary() == 1, "a slotless eSIM does not lead over slot 0");
    }

    private static void outOfServiceIsEmptyBarsOrAHollowRow() {
        SignalRows rows = RULES.arrange(List.of(sub(1, 0, 3, false), sub(2, 1, 3, false)), 1);
        check(rows.getPrimary() == 0, "an out-of-service primary draws empty bars, as the phone does");
        check(rows.getSecondary().equals(List.of(-1)), "an out-of-service secondary is a hollow row, not a missing one");
        rows = RULES.arrange(List.of(sub(1, 0, 3, true), sub(2, 1, -1, true)), 1);
        check(rows.getSecondary().equals(List.of(-1)), "a secondary not read yet is hollow too");
    }

    private static void atMostThreeRowsInSlotOrder() {
        List<SubscriptionSignal> subs = Arrays.asList(
                sub(50, 4, 0, true), sub(10, 0, 4, true), sub(40, 3, 1, true), sub(20, 1, 3, true), sub(30, 2, 2, true));
        SignalRows rows = RULES.arrange(subs, 10);
        check(rows.getSecondary().equals(List.of(3, 2, 1)), "three rows, in slot order, the fifth SIM dropped: " + rows);
        check(rows.getCount() == 5, "but all five are counted");
        check(SimSignal.MAX_SECONDARY == 3, "three secondary rows");
    }

    private static void levelsAreClamped() {
        SignalRows rows = RULES.arrange(List.of(sub(1, 0, 9, true), sub(2, 1, 7, true)), 1);
        check(rows.getPrimary() == 4 && rows.getSecondary().equals(List.of(4)), "a level past 4 draws four");
    }

    private static void networkLabels() {
        check("5G".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_LTE, SimSignal.OVERRIDE_NR_NSA)),
                "LTE with the NR_NSA override is 5G, as the status bar shows it");
        check("5G".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_LTE, SimSignal.OVERRIDE_NR_ADVANCED)), "and 5G+ is 5G");
        check("5G".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_NR, 0)), "standalone NR is 5G");
        check("LTE".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_LTE, 0)), "LTE");
        check("LTE".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_LTE_CA, 0)), "carrier-aggregated LTE is LTE");
        check("LTE".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_LTE, 1)), "the LTE_CA override is still LTE");
        check("H+".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_HSPAP, 0)), "HSPA+ is H+");
        check("3G".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_UMTS, 0)), "UMTS is 3G");
        check("E".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_EDGE, 0)), "EDGE is E");
        check("2G".equals(RULES.networkLabel(SimSignal.NETWORK_TYPE_GSM, 0)), "GSM is 2G");
        check("-".equals(RULES.networkLabel(0, 0)), "unknown is a dash");
        check("-".equals(RULES.networkLabel(18, 0)), "IWLAN (wifi calling) is a dash; the header says wifi from the transport");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        passed++;
    }
}
