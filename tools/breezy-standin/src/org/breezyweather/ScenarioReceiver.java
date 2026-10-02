package org.breezyweather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.IOException;
import java.util.Arrays;
import org.breezyweather.standin.Scenarios;

/**
 * Picks the scenario the provider serves, for the lab:
 *
 * <pre>
 *   adb shell am broadcast -n org.breezyweather/.ScenarioReceiver --es scenario alert
 * </pre>
 *
 * The name is kept in SharedPreferences, so it holds if the process is killed, and applies
 * from the next query. With no extra it only reports the current one. am prints the outcome
 * as the broadcast's data ("scenario alert", or "unknown scenario …" for a name with no
 * directory under scenarios/, which leaves the current one in place), and it is logged
 * under BreezyStandin too.
 */
public final class ScenarioReceiver extends BroadcastReceiver {
    private static final String PREFS = "standin";
    private static final String KEY = "scenario";

    /** The scenario the provider serves now. */
    static String current(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Scenarios.DEFAULT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String scenario = intent.getStringExtra("scenario");
        String result;
        if (scenario == null) {
            result = "scenario " + current(context);
        } else if (known(context, scenario)) {
            // commit, not apply: the next query may come straight after am returns.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, scenario).commit();
            result = "scenario " + scenario;
        } else {
            result = "unknown scenario " + scenario;
        }
        Log.i(WeatherProvider.TAG, result);
        if (isOrderedBroadcast()) setResultData(result);
    }

    private static boolean known(Context context, String scenario) {
        try {
            String[] names = context.getAssets().list("scenarios");
            return names != null && Arrays.asList(names).contains(scenario);
        } catch (IOException e) {
            return false;
        }
    }
}
