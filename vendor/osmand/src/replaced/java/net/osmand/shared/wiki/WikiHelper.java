package net.osmand.shared.wiki;

/** Stand-in for OsmAnd-shared's WikiHelper. Builds no URL: HeadLink never fetches Wikimedia images. */
public final class WikiHelper {
	public static final WikiHelper INSTANCE = new WikiHelper();

	private WikiHelper() {
	}

	public WikiImage getImageData(String imageDetails) {
		return new WikiImage();
	}
}
