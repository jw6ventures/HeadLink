package net.osmand;

import net.osmand.binary.BinaryMapDataObject;
import net.osmand.map.OsmandRegions;

import org.apache.commons.logging.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HeadLink's replacement for upstream's PlatformUtil, which OsmAnd's own Android build also
 * leaves out of its jar. Logs through java.util.logging (logcat on Android), with upstream's
 * info level lowered to FINE, and takes its XML parser from XmlPullParserFactory, which the
 * Android framework provides; neither needs a library in the APK.
 */
public class PlatformUtil {

	private static OsmandRegions osmandRegions;

	public static Log getLog(Class<?> cl) {
		return new JulLog(Logger.getLogger(cl.getName()));
	}

	public static void setOsmandRegions(OsmandRegions or) {
		osmandRegions = or;
	}

	public static OsmandRegions getOsmandRegions() throws IOException {
		// Upstream copies a bundled regions.ocbf (OsmAnd's download-region outlines) into the
		// working directory here. HeadLink ships no such file and downloads nothing, so
		// without one set it answers with an index that knows no region. The router asks it
		// which regions hold the start and the destination, to prefer HH data from those
		// files; with none known it falls back to each file's own bounds, which is what
		// HeadLink wants for the files the person chose.
		if (osmandRegions == null) {
			osmandRegions = new NoRegions();
		}
		return osmandRegions;
	}

	public static XmlPullParser newXMLPullParser() throws XmlPullParserException {
		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware(true);
		return factory.newPullParser();
	}

	public static XmlSerializer newSerializer() {
		try {
			return XmlPullParserFactory.newInstance().newSerializer();
		} catch (XmlPullParserException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class NoRegions extends OsmandRegions {
		NoRegions() {
			super(false);
		}

		@Override
		public List<BinaryMapDataObject> getRegionsToDownload(double lat, double lon) {
			return Collections.emptyList();
		}
	}

	private static final class JulLog implements Log {
		private final Logger logger;

		JulLog(Logger logger) {
			this.logger = logger;
		}

		private void log(Level level, Object message, Throwable t) {
			if (logger.isLoggable(level)) {
				logger.log(level, String.valueOf(message), t);
			}
		}

		@Override public void debug(Object message) { log(Level.FINE, message, null); }
		@Override public void debug(Object message, Throwable t) { log(Level.FINE, message, t); }
		@Override public void error(Object message) { log(Level.SEVERE, message, null); }
		@Override public void error(Object message, Throwable t) { log(Level.SEVERE, message, t); }
		@Override public void fatal(Object message) { log(Level.SEVERE, message, null); }
		@Override public void fatal(Object message, Throwable t) { log(Level.SEVERE, message, t); }
		// Upstream's info lines are routine progress, and some name the roads a route found;
		// they go out at FINE, which logcat does not show by default.
		@Override public void info(Object message) { log(Level.FINE, message, null); }
		@Override public void info(Object message, Throwable t) { log(Level.FINE, message, t); }
		@Override public boolean isDebugEnabled() { return logger.isLoggable(Level.FINE); }
		@Override public boolean isErrorEnabled() { return logger.isLoggable(Level.SEVERE); }
		@Override public boolean isFatalEnabled() { return logger.isLoggable(Level.SEVERE); }
		@Override public boolean isInfoEnabled() { return logger.isLoggable(Level.FINE); }
		@Override public boolean isTraceEnabled() { return logger.isLoggable(Level.FINEST); }
		@Override public boolean isWarnEnabled() { return logger.isLoggable(Level.WARNING); }
		@Override public void trace(Object message) { log(Level.FINEST, message, null); }
		@Override public void trace(Object message, Throwable t) { log(Level.FINEST, message, t); }
		@Override public void warn(Object message) { log(Level.WARNING, message, null); }
		@Override public void warn(Object message, Throwable t) { log(Level.WARNING, message, t); }
	}
}
