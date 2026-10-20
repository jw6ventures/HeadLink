// P8.2's turn prompts on a desktop JVM: VoicePrompts fed synthetic step traces at a steady
// speed, the way Guide feeds it one step a fix, and SpokenText's distances in words and
// road names. Both are pure Kotlin, so these are the classes the app runs. Nothing here
// is speech or the truck: the plan's section 5.2 thresholds are checked against themselves, and whether
// they are well placed is a drive's question.
import btools.router.VoiceHintReader;
import java.util.ArrayList;
import java.util.List;
import com.jw6ventures.headlink.LaterStep;
import com.jw6ventures.headlink.Maneuver;
import com.jw6ventures.headlink.Maneuvers;
import com.jw6ventures.headlink.NextStep;
import com.jw6ventures.headlink.PromptDetail;
import com.jw6ventures.headlink.PromptStage;
import com.jw6ventures.headlink.PromptTiming;
import com.jw6ventures.headlink.RouteFollower;
import com.jw6ventures.headlink.SpeedUnit;
import com.jw6ventures.headlink.SpokenText;
import com.jw6ventures.headlink.VoicePrompt;
import com.jw6ventures.headlink.VoicePrompts;
import com.jw6ventures.headlink.VoiceSettings;

public final class VoicePromptsTest {
    private static int passed;

    private static final double MPH30 = 30 / 2.2369362920544;
    private static final double MPH70 = 70 / 2.2369362920544;
    private static final double PREP30 = MPH30 * 15;
    private static final String START = "Start Road";

    public static void main(String[] args) {
        distancesInWords();
        roadNamesSpoken();
        ordinalsAndRoundabouts();
        theSpokenSentenceIsTheCardsSentence();
        theSample();
        thresholds();
        aCityLegAt30();
        aHighwayExitAt70();
        noSpeedUsesTheStepsOwnPace();
        twoTurns100mApart();
        keepRightBeforeTheExitInFull();
        aRouteJoinedInsidePrepare();
        straightIsSilentUnlessTheNameChanges();
        aRerouteLandingOnTheSameTurn();
        arrival();
        stageToggles();
        timingFactors();
        turningGuidanceOnConfirms();
        System.out.println("jni-host-test: ok " + passed + " voice prompt checks");
    }

    // --- wording ------------------------------------------------------------------------

    private static void distancesInWords() {
        Object[][] imperial = {
            {10.0, "100 feet"},                        // never zero
            {100.0, "300 feet"},                       // 328 ft, to the hundred
            {150.0, "500 feet"},                       // Prepare's minimum
            {170.0, "600 feet"},                       // 558 ft: past 0.1 mi, still feet
            {195.3, "600 feet"},                       // 641 ft: a 30 mph Prepare
            {200.3, "700 feet"},                       // 657 ft
            {300.0, "1000 feet"},                      // 984 ft
            {304.8, "1000 feet"},                      // exactly 1000 ft: the last in feet
            {310.0, "a quarter mile"},                 // 1017 ft: quarters from here
            {400.0, "a quarter mile"},
            {800.0, "half a mile"},                    // Early's minimum
            {1200.0, "three quarters of a mile"},
            {1609.344, "1 mile"},
            {1900.0, "1 mile"},                        // 1.18 mi: half-mile steps
            {2414.0, "1 and a half miles"},
            {3219.0, "2 miles"},
            {4023.0, "2 and a half miles"},
        };
        for (Object[] row : imperial) {
            String said = SpokenText.INSTANCE.distance((Double) row[0], SpeedUnit.MPH);
            check(said.equals(row[1]), row[0] + " m is \"" + row[1] + "\", not \"" + said + "\"");
        }
        Object[][] metric = {
            {20.0, "50 metres"},
            {140.0, "150 metres"},
            {820.0, "800 metres"},
            {980.0, "1 kilometre"},                    // rounds to 1000 m: kilometres from there
            {1200.0, "1 kilometre"},
            {1500.0, "1 and a half kilometres"},
            {2000.0, "2 kilometres"},
        };
        for (Object[] row : metric) {
            String said = SpokenText.INSTANCE.distance((Double) row[0], SpeedUnit.KMH);
            check(said.equals(row[1]), row[0] + " m is \"" + row[1] + "\", not \"" + said + "\"");
        }
    }

    private static void roadNamesSpoken() {
        String[][] table = {
            {"I 35", "Interstate 35"},
            {"I-35", "Interstate 35"},
            {"IH 35", "Interstate 35"},
            {"US 290", "U S 290"},
            {"US Highway 290", "U S Highway 290"},
            {"SH 71", "State Highway 71"},
            {"TX 71", "State Highway 71"},
            {"FM 1826", "Farm to Market 1826"},
            {"RM 2222", "Ranch to Market 2222"},
            {"CR 101", "County Road 101"},
            {"Loop 1", "Loop 1"},
            {"US 290 W", "U S 290 West"},
            {"I 35 N", "Interstate 35 North"},
            {"Burnet Road S", "Burnet Road South"},
            {"Parmer Lane E", "Parmer Lane East"},
            // Unknown text passes through: no prefix, no number, a lone letter, a word.
            {"Main Street", "Main Street"},
            {"Avenue I", "Avenue I"},
            {"W", "W"},
            {"Ranch Road 12", "Ranch Road 12"},
            {"FIR 5", "FIR 5"},
        };
        for (String[] row : table) {
            String said = SpokenText.INSTANCE.road(row[0]);
            check(said.equals(row[1]), "\"" + row[0] + "\" is said \"" + row[1] + "\", not \"" + said + "\"");
        }
    }

    private static void ordinalsAndRoundabouts() {
        String[] ordinals = {"1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st"};
        int[] numbers = {1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 101};
        for (int i = 0; i < numbers.length; i++) {
            check(SpokenText.INSTANCE.ordinal(numbers[i]).equals(ordinals[i]), numbers[i] + " is " + ordinals[i]);
        }
        Maneuver r = Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT, 2);
        check(r.getInstruction().equals("At the roundabout, take exit 2"), "the card still says exit 2");
        check(SpokenText.INSTANCE.maneuver(r).getInstruction().equals("At the roundabout, take the 2nd exit"),
            "the voice says the 2nd exit");
        NextStep step = step(1, 0, r, "Oak Avenue", START, 140, 10, new LaterStep[0]);
        VoicePrompts vp = new VoicePrompts();
        VoicePrompt p = vp.onStep(step, MPH30, 0L);
        check(p != null && p.getText().equals("In 500 feet, at the roundabout, take the 2nd exit onto Oak Avenue"),
            "a roundabout prompt: " + text(p));
        Maneuver through = Maneuvers.INSTANCE.of(VoiceHintReader.ROUNDABOUT, 0);
        check(SpokenText.INSTANCE.maneuver(through).getInstruction().equals("Go through the roundabout"),
            "no exit number, none spoken");
    }

    private static void theSpokenSentenceIsTheCardsSentence() {
        Maneuver keep = Maneuvers.INSTANCE.of(VoiceHintReader.KEEP_LEFT, 0);
        NextStep stays = step(1, 0, keep, "I 35", "I 35", 100, 5, new LaterStep[0]);
        check(stays.getInstruction().equals("Keep left to stay on I 35"), "the card's sentence");
        check(SpokenText.INSTANCE.instruction(stays, true).equals("Keep left to stay on Interstate 35"),
            "the same sentence, the road said");
        check(SpokenText.INSTANCE.instruction(stays, false).equals("Keep left"), "street names off");
        Maneuver right = Maneuvers.INSTANCE.of(VoiceHintReader.TURN_RIGHT, 0);
        NextStep onto = step(1, 0, right, "FM 1826", "Main Street", 100, 5, new LaterStep[0]);
        check(SpokenText.INSTANCE.instruction(onto, true).equals("Turn right onto Farm to Market 1826"), "onto a road");
        check(SpokenText.INSTANCE.instruction(onto, false).equals("Turn right"), "onto a road, names off");
        NextStep unnamed = step(1, 0, right, null, "Main Street", 100, 5, new LaterStep[0]);
        check(SpokenText.INSTANCE.instruction(unnamed, true).equals("Turn right"), "no name, none said");
    }

    // P8.3: Play a sample's words, built as a Prepare prompt is.
    private static void theSample() {
        check(SpokenText.INSTANCE.sample(SpeedUnit.MPH, true).equals("In 500 feet, turn right onto Main Street"),
            "the plan's sample, word for word: " + SpokenText.INSTANCE.sample(SpeedUnit.MPH, true));
        check(SpokenText.INSTANCE.sample(SpeedUnit.MPH, false).equals("In 500 feet, turn right"), "street names off");
        check(SpokenText.INSTANCE.sample(SpeedUnit.KMH, true).equals("In 150 metres, turn right onto Main Street"),
            "in metric, the same place: " + SpokenText.INSTANCE.sample(SpeedUnit.KMH, true));
    }

    private static void thresholds() {
        double early70 = VoicePrompts.Companion.threshold(VoicePrompts.EARLY, MPH70, PromptTiming.NORMAL);
        check(Math.abs(early70 - MPH70 * 50) < 1e-9 && early70 > 1560 && early70 < 1570, "Early at 70 mph is about 0.97 mi");
        double prepare70 = VoicePrompts.Companion.threshold(VoicePrompts.PREPARE, MPH70, PromptTiming.NORMAL);
        check(prepare70 > 465 && prepare70 < 475, "Prepare at 70 mph is about 470 m");
        check(VoicePrompts.Companion.threshold(VoicePrompts.EARLY, MPH30, PromptTiming.NORMAL) == 800.0, "Early at 30 mph is 800 m");
        // The plan's text says Prepare is 150 m at 30 mph; its own formula gives 13.4 m/s x 15 s,
        // about 201 m, and the formula is what is built.
        check(Math.abs(VoicePrompts.Companion.threshold(VoicePrompts.PREPARE, MPH30, PromptTiming.NORMAL) - MPH30 * 15) < 1e-9,
            "Prepare at 30 mph is speed x 15 s, about 201 m");
        check(VoicePrompts.Companion.threshold(VoicePrompts.PREPARE, 5.0, PromptTiming.NORMAL) == 150.0, "Prepare at 11 mph is 150 m");
        check(VoicePrompts.Companion.threshold(VoicePrompts.AT_TURN, 0.0, PromptTiming.NORMAL) == 30.0, "standing still, the minimum");
    }

    // --- whole approaches ---------------------------------------------------------------

    private static void aCityLegAt30() {
        Leg[] legs = {
            leg(1000, VoiceHintReader.TURN_RIGHT, "Main Street"),
            leg(1300, VoiceHintReader.TURN_LEFT, "Oak Avenue"),
            leg(1810, VoiceHintReader.END, null),
        };
        List<Heard> heard = drive(new VoicePrompts(), 1, legs, MPH30, true, 0);
        expect(heard, "city leg", new Object[][] {
            {PromptStage.EARLY, "In half a mile, turn right onto Main Street", 800.0 - MPH30, 800.0},
            // 195.3 m (641 ft): feet up to 1000 ft, so 600 feet.
            {PromptStage.PREPARE, "In 600 feet, turn right onto Main Street", PREP30 - MPH30, PREP30},
            {PromptStage.AT_TURN, "Turn right onto Main Street", 4 * MPH30 - MPH30, 4 * MPH30},
            // 300 m leg: shorter than twice Prepare (402 m), so no Early.
            // 200.3 m (657 ft).
            {PromptStage.PREPARE, "In 700 feet, turn left onto Oak Avenue", PREP30 - MPH30, PREP30},
            {PromptStage.AT_TURN, "Turn left onto Oak Avenue", 4 * MPH30 - MPH30, 4 * MPH30},
            // 200.7 m (658 ft).
            {PromptStage.PREPARE, "In 700 feet, arrive at the destination", PREP30 - MPH30, PREP30},
            {PromptStage.ARRIVAL, "You have arrived", -1.0, 1e9},
        });
    }

    private static void aHighwayExitAt70() {
        Leg[] legs = {
            leg(5000, VoiceHintReader.EXIT_RIGHT, "FM 1826"),
            leg(5800, VoiceHintReader.TURN_LEFT, "Main Street"),
            leg(8000, VoiceHintReader.END, null),
        };
        double early = MPH70 * 50;
        double prepare = MPH70 * 15;
        double atTurn = MPH70 * 4;
        List<Heard> heard = drive(new VoicePrompts(), 1, legs, MPH70, true, 0);
        expect(heard, "highway exit", new Object[][] {
            {PromptStage.EARLY, "In 1 mile, take the exit on the right onto Farm to Market 1826", early - MPH70, early},
            {PromptStage.PREPARE, "In a quarter mile, take the exit on the right onto Farm to Market 1826", prepare - MPH70, prepare},
            {PromptStage.AT_TURN, "Take the exit on the right onto Farm to Market 1826", atTurn - MPH70, atTurn},
            // 800 m after the exit: under twice Prepare at 70, so no Early.
            {PromptStage.PREPARE, "In a quarter mile, turn left onto Main Street", prepare - MPH70, prepare},
            {PromptStage.AT_TURN, "Turn left onto Main Street", atTurn - MPH70, atTurn},
        }, 5);
        check(heard.get(5).stage == PromptStage.PREPARE && heard.get(5).text.equals("In a quarter mile, arrive at the destination"),
            "the final approach is Prepare only: " + heard.get(5).text);
        check(heard.size() == 7 && heard.get(6).stage == PromptStage.ARRIVAL, "then arrival, and nothing else");
    }

    private static void noSpeedUsesTheStepsOwnPace() {
        Leg[] legs = {leg(5000, VoiceHintReader.EXIT_RIGHT, "FM 1826"), leg(8000, VoiceHintReader.END, null)};
        List<Heard> heard = drive(new VoicePrompts(), 1, legs, MPH70, false, 0);
        double early = MPH70 * 50;
        check(heard.get(0).stage == PromptStage.EARLY && heard.get(0).metres <= early + MPH70 / 2 + 1 && heard.get(0).metres > early - MPH70 - 1,
            "no speed: Early from BRouter's pace, at " + (int) heard.get(0).metres + " m");
        check(heard.get(2).stage == PromptStage.AT_TURN, "At the turn still fires as seconds round to 0");
    }

    private static void twoTurns100mApart() {
        Leg[] legs = {
            leg(1000, VoiceHintReader.TURN_LEFT, "A Street"),
            leg(1100, VoiceHintReader.TURN_RIGHT, "B Street"),
            leg(1800, VoiceHintReader.END, null),
        };
        List<Heard> full = drive(new VoicePrompts(), 1, legs, MPH30, true, 0);
        check(stages(full).equals("early,prepare,at_turn,at_turn,prepare,arrival"), "Full: the second Prepare is chained away: " + stages(full));
        check(full.get(2).text.equals("Turn left onto A Street, then turn right"), "the chained then: " + full.get(2).text);
        check(full.get(3).text.equals("Turn right onto B Street"), "the second turn's own At the turn");

        VoicePrompts brief = new VoicePrompts(settings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.BRIEF), SpeedUnit.MPH);
        List<Heard> b = drive(brief, 1, legs, MPH30, true, 0);
        check(stages(b).equals("early,prepare,at_turn,prepare,at_turn,prepare,arrival"), "Brief: no chaining: " + stages(b));
        check(b.get(2).text.equals("Turn left onto A Street"), "Brief says no then");
        check(b.get(3).text.equals("In 300 feet, turn right onto B Street"), "the second turn's Prepare: " + b.get(3).text);

        VoicePrompts noTurn = new VoicePrompts(settings(true, true, true, false, PromptTiming.NORMAL, PromptDetail.FULL), SpeedUnit.MPH);
        List<Heard> n = drive(noTurn, 1, legs, MPH30, true, 0);
        check(stages(n).equals("early,prepare,prepare,prepare,arrival"), "At the turn off removes the then, keeps the Prepare: " + stages(n));
    }

    private static void keepRightBeforeTheExitInFull() {
        Leg[] legs = {
            leg(3000, VoiceHintReader.KEEP_RIGHT, "I 35"),
            leg(4200, VoiceHintReader.EXIT_RIGHT, "US 290 W"),
            leg(9000, VoiceHintReader.END, null),
        };
        List<Heard> full = drive(new VoicePrompts(), 1, legs, MPH70, true, 0);
        Heard prep = find(full, 1, PromptStage.PREPARE);
        check(prep != null && prep.text.equals("In a quarter mile, keep right, then take the exit on the right onto U S 290 West"),
            "Full: keep right before the exit, at Prepare: " + (prep == null ? "none" : prep.text));
        Heard turn = find(full, 1, PromptStage.AT_TURN);
        check(turn != null && turn.text.equals("Take the exit on the right onto U S 290 West"), "not at the turn");
        VoicePrompts brief = new VoicePrompts(settings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.BRIEF), SpeedUnit.MPH);
        Heard briefPrep = find(drive(brief, 1, legs, MPH70, true, 0), 1, PromptStage.PREPARE);
        check(briefPrep != null && briefPrep.text.equals("In a quarter mile, take the exit on the right onto U S 290 West"),
            "Brief: no keep");
    }

    private static void aRouteJoinedInsidePrepare() {
        Leg[] legs = {leg(1000, VoiceHintReader.TURN_RIGHT, "Main Street"), leg(1500, VoiceHintReader.END, null)};
        List<Heard> inside = drive(new VoicePrompts(), 1, legs, MPH30, true, 1000 - 120);
        check(inside.get(0).stage == PromptStage.PREPARE && inside.get(0).metres == 120.0, "first seen inside Prepare: Prepare at once");
        check(inside.get(1).stage == PromptStage.AT_TURN, "then At the turn, no Early");
        List<Heard> atTurn = drive(new VoicePrompts(), 1, legs, MPH30, true, 1000 - 40);
        check(atTurn.get(0).stage == PromptStage.AT_TURN && atTurn.get(0).metres == 40.0, "first seen at the turn: only that");
        check(atTurn.get(1).stage == PromptStage.PREPARE && atTurn.get(1).stepIndex == 1, "then the next step");
        List<Heard> early = drive(new VoicePrompts(), 1, legs, MPH30, true, 1000 - 500);
        check(stages(early).startsWith("early,prepare,at_turn"), "first seen inside Early (500 m, long enough): Early at once");
    }

    private static void straightIsSilentUnlessTheNameChanges() {
        Leg[] same = {leg(1000, VoiceHintReader.CONTINUE, START), leg(1500, VoiceHintReader.END, null)};
        List<Heard> silent = drive(new VoicePrompts(), 1, same, MPH30, true, 0);
        check(stages(silent).equals("prepare,arrival") && silent.get(0).stepIndex == 1, "a straight on the same road says nothing");
        Leg[] renamed = {leg(1000, VoiceHintReader.CONTINUE, "Lamar Boulevard"), leg(1500, VoiceHintReader.END, null)};
        List<Heard> heard = drive(new VoicePrompts(), 1, renamed, MPH30, true, 0);
        check(heard.get(0).stage == PromptStage.PREPARE && heard.get(0).stepIndex == 0
            && heard.get(0).text.equals("In 600 feet, continue straight onto Lamar Boulevard"), "a name change, at Prepare only");
        check(heard.get(1).stepIndex == 1, "and nothing else for it");
        VoicePrompts noNames = new VoicePrompts(new VoiceSettings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL,
            false, true, true), SpeedUnit.MPH);
        check(drive(noNames, 1, renamed, MPH30, true, 0).get(0).stepIndex == 1, "street names off: a name change has nothing to say");
    }

    private static void aRerouteLandingOnTheSameTurn() {
        Maneuver right = Maneuvers.INSTANCE.of(VoiceHintReader.TURN_RIGHT, 0);
        Maneuver left = Maneuvers.INSTANCE.of(VoiceHintReader.TURN_LEFT, 0);
        for (int variant = 0; variant < 3; variant++) {
            VoicePrompts vp = new VoicePrompts();
            long t = 0;
            // Route 1: approach until Prepare has been said, at 140 m.
            double m = 400;
            VoicePrompt prepare = null;
            for (; m > 135; m -= MPH30, t += 1000) {
                VoicePrompt p = vp.onStep(step(1, 0, right, "Main Street", START, m, 30, new LaterStep[0]), MPH30, t);
                if (p != null) prepare = p;
            }
            check(prepare != null && prepare.getStage() == PromptStage.PREPARE, "Prepare said on the first route");
            // Off the route: Guide publishes REROUTING on each fix; said once.
            int rerouting = 0;
            long gap = variant == 2 ? 25_000 : 3_000;
            for (long k = 0; k < gap; k += 1000) {
                if (vp.onRerouting() != null) rerouting++;
            }
            check(rerouting == 1, "Rerouting said once per re-route");
            t += gap;
            m -= MPH30 * gap / 1000.0;
            if (variant == 2) m += MPH30 * gap / 1000.0; // circled back: the turn is ahead again
            Maneuver next = variant == 1 ? left : right;
            List<VoicePrompt> heard = new ArrayList<>();
            for (; m > 0; m -= MPH30, t += 1000) {
                VoicePrompt p = vp.onStep(step(2, 0, next, "Main Street", START, m, 30, new LaterStep[0]), MPH30, t);
                if (p != null) heard.add(p);
            }
            String got = "";
            for (VoicePrompt p : heard) got += p.getStage().getLabel() + ",";
            if (variant == 0) {
                check(got.equals("at_turn,"), "the same turn on a new route: Prepare not repeated: " + got);
            } else if (variant == 1) {
                check(got.equals("prepare,at_turn,"), "a different maneuver at the same point is said: " + got);
            } else {
                check(got.equals("prepare,at_turn,"), "more than 20 s later it is said again: " + got);
            }
            // Re-armed by the new route.
            check(vp.onRerouting() != null, "the next re-route is announced");
        }
        VoicePrompts quiet = new VoicePrompts(new VoiceSettings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL,
            true, false, true), SpeedUnit.MPH);
        check(quiet.onRerouting() == null, "announce rerouting off");
        VoicePrompt r = new VoicePrompts().onRerouting();
        check(r != null && r.getText().equals("Rerouting") && r.getStage() == PromptStage.REROUTE, "the reroute prompt");
    }

    private static void arrival() {
        Leg[] legs = {leg(1000, VoiceHintReader.END, null)};
        VoicePrompts vp = new VoicePrompts();
        List<Heard> heard = drive(vp, 1, legs, MPH30, true, 0);
        check(stages(heard).equals("prepare,arrival"), "a destination: Prepare, then arrival: " + stages(heard));
        check(heard.get(0).text.equals("In 600 feet, arrive at the destination"), "the final approach at 195 m");
        check(vp.onArrived() == null, "arrival said once");
        VoicePrompts off = new VoicePrompts(new VoiceSettings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL,
            true, true, false), SpeedUnit.MPH);
        check(stages(drive(off, 1, legs, MPH30, true, 0)).equals("prepare"), "announce arrival off");
        // A new route arms it again.
        drive(vp, 2, legs, MPH30, true, 500);
        check(vp.onArrived() == null, "the second route's arrival was said in its drive");
    }

    private static void stageToggles() {
        Leg[] legs = {leg(2000, VoiceHintReader.TURN_RIGHT, "Main Street"), leg(3000, VoiceHintReader.END, null)};
        check(stages(drive(new VoicePrompts(settings(true, false, true, true, PromptTiming.NORMAL, PromptDetail.FULL),
            SpeedUnit.MPH), 1, legs, MPH30, true, 0)).equals("prepare,at_turn,prepare,arrival"), "Early off");
        check(stages(drive(new VoicePrompts(settings(true, true, false, true, PromptTiming.NORMAL, PromptDetail.FULL),
            SpeedUnit.MPH), 1, legs, MPH30, true, 0)).equals("early,at_turn,arrival"), "Prepare off, the final approach too");
        check(stages(drive(new VoicePrompts(settings(true, true, true, false, PromptTiming.NORMAL, PromptDetail.FULL),
            SpeedUnit.MPH), 1, legs, MPH30, true, 0)).equals("early,prepare,prepare,arrival"), "At the turn off");
        check(stages(drive(new VoicePrompts(settings(false, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL),
            SpeedUnit.MPH), 1, legs, MPH30, true, 0)).equals(""), "guidance off says nothing at all");
    }

    private static void timingFactors() {
        Leg[] legs = {leg(5000, VoiceHintReader.EXIT_RIGHT, "FM 1826"), leg(9000, VoiceHintReader.END, null)};
        PromptTiming[] timings = {PromptTiming.EARLIER, PromptTiming.NORMAL, PromptTiming.LATER};
        double[] factors = {1.3, 1.0, 0.75};
        for (int i = 0; i < 3; i++) {
            check(timings[i].getFactor() == factors[i], timings[i].getLabel() + " is x" + factors[i]);
            List<Heard> heard = drive(new VoicePrompts(settings(true, true, true, true, timings[i], PromptDetail.FULL),
                SpeedUnit.MPH), 1, legs, MPH70, true, 0);
            double[] leads = {50, 15, 4};
            for (int s = 0; s < 3; s++) {
                double at = MPH70 * leads[s] * factors[i];
                Heard h = heard.get(s);
                check(h.metres <= at && h.metres > at - MPH70, timings[i].getLabel() + " stage " + s + " at " + (int) at + " m, heard at " + (int) h.metres);
            }
        }
        // Minimums do not scale: at 30 mph Later is still 800 / 150 / 30 m.
        check(VoicePrompts.Companion.threshold(VoicePrompts.EARLY, MPH30, PromptTiming.LATER) == 800.0, "Later keeps Early's minimum");
        check(VoicePrompts.Companion.threshold(VoicePrompts.AT_TURN, 5.0, PromptTiming.EARLIER) == 30.0, "Earlier keeps At the turn's minimum");
        check(VoicePrompts.Companion.threshold(VoicePrompts.PREPARE, 20.0, PromptTiming.EARLIER) == 20.0 * 15 * 1.3, "Earlier scales the lead");
    }

    private static void turningGuidanceOnConfirms() {
        Maneuver right = Maneuvers.INSTANCE.of(VoiceHintReader.TURN_RIGHT, 0);
        VoicePrompts vp = new VoicePrompts(settings(false, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL), SpeedUnit.MPH);
        long t = 0;
        double m = 1000;
        for (; m > 400; m -= MPH30, t += 1000) {
            check(vp.onStep(step(1, 0, right, "Main Street", START, m, 60, new LaterStep[0]), MPH30, t) == null, "off: silent");
        }
        check(vp.confirm(step(1, 0, right, "Main Street", START, m, 60, new LaterStep[0]), MPH30, t) == null, "still off: no confirmation");
        vp.setSettings(settings(true, true, true, true, PromptTiming.NORMAL, PromptDetail.FULL));
        VoicePrompt c = vp.confirm(step(1, 0, right, "Main Street", START, m, 60, new LaterStep[0]), MPH30, t);
        check(c != null && c.getText().equals("In a quarter mile, turn right onto Main Street") && c.getStage() == PromptStage.EARLY,
            "on: the current prompt at once: " + text(c));
        // The Early zone was passed while off; the next thing said is Prepare.
        List<VoicePrompt> after = new ArrayList<>();
        for (m -= MPH30, t += 1000; m > 0; m -= MPH30, t += 1000) {
            VoicePrompt p = vp.onStep(step(1, 0, right, "Main Street", START, m, 60, new LaterStep[0]), MPH30, t);
            if (p != null) after.add(p);
        }
        check(after.size() == 2 && after.get(0).getStage() == PromptStage.PREPARE && after.get(1).getStage() == PromptStage.AT_TURN,
            "then carries on normally, with no backlog");
        // Confirmed inside Prepare: that stage, used up.
        VoicePrompts inside = new VoicePrompts();
        VoicePrompt ci = inside.confirm(step(1, 0, right, "Main Street", START, 120, 9, new LaterStep[0]), MPH30, 0L);
        check(ci != null && ci.getStage() == PromptStage.PREPARE, "confirmed inside Prepare counts as Prepare");
        check(inside.onStep(step(1, 0, right, "Main Street", START, 110, 8, new LaterStep[0]), MPH30, 1000L) == null,
            "and is not said twice");
        check(inside.confirm(null, MPH30, 0L) == null, "no step, no confirmation");
    }

    // --- the harness --------------------------------------------------------------------

    private static final class Leg {
        final double along;
        final Maneuver maneuver;
        final String onto;

        Leg(double along, Maneuver maneuver, String onto) {
            this.along = along;
            this.maneuver = maneuver;
            this.onto = onto;
        }
    }

    private static final class Heard {
        final PromptStage stage;
        final String text;
        final double metres;
        final int stepIndex;

        Heard(PromptStage stage, String text, double metres, int stepIndex) {
            this.stage = stage;
            this.text = text;
            this.metres = metres;
            this.stepIndex = stepIndex;
        }
    }

    private static Leg leg(double along, int command, String onto) {
        return new Leg(along, Maneuvers.INSTANCE.of(command, 0), onto);
    }

    private static VoiceSettings settings(boolean enabled, boolean early, boolean prepare, boolean atTurn,
        PromptTiming timing, PromptDetail detail) {
        return new VoiceSettings(enabled, early, prepare, atTurn, timing, detail, true, true, true);
    }

    private static NextStep step(long generation, int k, Maneuver m, String onto, String road, double metres, int seconds,
        LaterStep[] later) {
        String shownOnto = m.getType() == Maneuvers.DESTINATION ? null : onto;
        return new NextStep(generation * RouteFollower.STEP_ID_STRIDE + k, m,
            Maneuvers.INSTANCE.instruction(m, shownOnto, road), shownOnto, road, metres, seconds,
            metres, seconds, List.of(later));
    }

    /**
     * One fix a second at [speed] from [start] metres along, as RouteFollower would count
     * down: the next leg is the first strictly ahead, arrival within 30 m of the end. With
     * [giveSpeed] false only BRouter's seconds (at the same pace) say how fast.
     */
    private static List<Heard> drive(VoicePrompts vp, long generation, Leg[] legs, double speed, boolean giveSpeed, double start) {
        List<Heard> heard = new ArrayList<>();
        double total = legs[legs.length - 1].along;
        long t = 0;
        for (double p = start; ; p += speed, t += 1000) {
            if (total - p <= RouteFollower.ARRIVE_M) {
                VoicePrompt a = vp.onArrived();
                if (a != null) heard.add(new Heard(a.getStage(), a.getText(), 0, legs.length));
                check(vp.onArrived() == null, "arrival once, however often published");
                return heard;
            }
            int k = 0;
            while (legs[k].along <= p) k++;
            Leg leg = legs[k];
            List<LaterStep> later = new ArrayList<>();
            for (int j = k + 1; j < Math.min(legs.length, k + 1 + RouteFollower.LATER_STEPS); j++) {
                later.add(new LaterStep(legs[j].maneuver, legs[j].along - p));
            }
            double metres = leg.along - p;
            String road = k == 0 ? START : legs[k - 1].onto;
            NextStep step = step(generation, k, leg.maneuver, leg.onto, road, metres, (int) Math.round(metres / speed),
                later.toArray(new LaterStep[0]));
            VoicePrompt prompt = vp.onStep(step, giveSpeed ? speed : null, t);
            if (prompt != null) heard.add(new Heard(prompt.getStage(), prompt.getText(), metres, k));
        }
    }

    private static void expect(List<Heard> heard, String what, Object[][] rows) {
        expect(heard, what, rows, rows.length);
        check(heard.size() == rows.length, what + ": exactly " + rows.length + " prompts, got " + stages(heard));
    }

    /** The first [count] of [rows]: stage, text, and the metres they were heard at, (low, high]. */
    private static void expect(List<Heard> heard, String what, Object[][] rows, int count) {
        check(heard.size() >= count, what + ": at least " + count + " prompts, got " + stages(heard));
        for (int i = 0; i < count; i++) {
            Heard h = heard.get(i);
            check(h.stage == rows[i][0], what + " #" + i + " is " + rows[i][0] + ", not " + h.stage);
            check(h.text.equals(rows[i][1]), what + " #" + i + " says \"" + rows[i][1] + "\", not \"" + h.text + "\"");
            double low = (Double) rows[i][2];
            double high = (Double) rows[i][3];
            check(h.metres > low && h.metres <= high + 1e-9, what + " #" + i + " heard at " + h.metres + " m, not in (" + low + ", " + high + "]");
        }
    }

    private static Heard find(List<Heard> heard, int stepIndex, PromptStage stage) {
        for (Heard h : heard) if (h.stepIndex == stepIndex && h.stage == stage) return h;
        return null;
    }

    private static String stages(List<Heard> heard) {
        StringBuilder out = new StringBuilder();
        for (Heard h : heard) out.append(out.length() == 0 ? "" : ",").append(h.stage.getLabel());
        return out.toString();
    }

    private static String text(VoicePrompt p) {
        return p == null ? "none" : p.getText();
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("voice prompts: " + what);
        passed++;
    }
}
