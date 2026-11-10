package net.osmand.util;

/**
 * HeadLink's stand-in for upstream's TransliterationHelper, which romanises names through
 * junidecode. HeadLink always shows names as the map spells them and never asks for a
 * transliterated one, so this keeps upstream's methods and returns the text unchanged,
 * and junidecode stays out of the APK.
 */
public class TransliterationHelper {

	private static boolean japanese;

	private TransliterationHelper() {
	}

	public static boolean isJapanese() {
		return japanese;
	}

	public static void setJapanese(boolean japanese) {
		TransliterationHelper.japanese = japanese;
	}

	public static String transliterate(String text) {
		return text;
	}
}
