package net.osmand.shared.gpx;

/** Stand-in for OsmAnd-shared's GpxFile: the one constant the vendored readers use. */
public final class GpxFile {
	/** How OsmAnd's generator escapes ':' in tag keys. Must match upstream GpxFile.XML_COLON. */
	public static final String XML_COLON = "_-_";

	private GpxFile() {
	}
}
