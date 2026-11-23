package net.osmand.shared.util;

/**
 * Stand-in for OsmAnd-shared's PlatformUtil. The only caller in the vendored tree is search's
 * link resolver, which HeadLink never runs; the stand-in has no network at all, so it answers
 * that nothing could be resolved, the same as upstream does offline.
 */
public final class PlatformUtil {
	public static final PlatformUtil INSTANCE = new PlatformUtil();

	private PlatformUtil() {
	}

	public NetworkAPI getNetworkAPI() {
		return NetworkAPI.NONE;
	}

	public interface NetworkAPI {
		NetworkAPI NONE = url -> null;

		String resolveRedirectUrl(String url);
	}
}
