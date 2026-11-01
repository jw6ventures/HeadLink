package net.osmand.shared.routing;

/**
 * OsmAnd-shared's GeneralRouterProfile (Kotlin, commonMain) written out in Java, so the
 * vendored router needs nothing from the Kotlin multiplatform module. Same constants in the
 * same order; getBaseProfile() is upstream's name.lowercase().
 */
public enum GeneralRouterProfile {
	CAR, PEDESTRIAN, BICYCLE, BOAT, SKI, MOPED, TRAIN, PUBLIC_TRANSPORT, HORSEBACKRIDING;

	public String getBaseProfile() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}
}
