package com.jwetherell.openmap.common;

/**
 * HeadLink's own stand-in, not BBN OpenMap's MoreMath, which is not vendored because its
 * licence is not GPL-compatible (README.md, "Replaced files"). Upstream's MapUtils imports
 * one constant from it, a quarter of pi, and keeps that import unchanged with this class.
 */
public final class MoreMath {
	/** A quarter of pi. */
	public static final double QUAD_PI_D = Math.PI / 4.0d;

	private MoreMath() {
	}
}
