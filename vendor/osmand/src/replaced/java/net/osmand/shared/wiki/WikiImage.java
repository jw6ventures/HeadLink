package net.osmand.shared.wiki;

/** Stand-in for OsmAnd-shared's WikiImage. HeadLink shows no Wikimedia images, so every URL is empty. */
public final class WikiImage {
	public String getImageIconUrl() {
		return "";
	}

	public String getImageStubUrl() {
		return "";
	}
}
