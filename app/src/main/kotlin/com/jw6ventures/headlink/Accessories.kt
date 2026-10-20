package com.jw6ventures.headlink

import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager

/** Identity fields from `res/xml/accessory_filter.xml`; null fields match anything. */
data class AccessoryFilter(val manufacturer: String?, val model: String?, val version: String?) {
    fun matches(accessory: UsbAccessory): Boolean =
        (manufacturer == null || manufacturer == accessory.manufacturer) &&
            (model == null || model == accessory.model) &&
            (version == null || version == accessory.version)

    override fun toString(): String = "manufacturer=$manufacturer model=$model version=$version"

    companion object {
        /** Reads the first `usb-accessory` entry, the same one the manifest attach filter uses. */
        fun load(resources: Resources): AccessoryFilter {
            resources.getXml(R.xml.accessory_filter).use { parser ->
                while (parser.next() != XmlResourceParser.END_DOCUMENT) {
                    if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "usb-accessory") {
                        return AccessoryFilter(
                            parser.getAttributeValue(null, "manufacturer"),
                            parser.getAttributeValue(null, "model"),
                            parser.getAttributeValue(null, "version"),
                        )
                    }
                }
            }
            error("accessory_filter.xml has no usb-accessory entry")
        }
    }
}

sealed interface Enumeration {
    data object None : Enumeration
    data class Mismatch(val accessory: UsbAccessory) : Enumeration
    data class Match(val accessory: UsbAccessory) : Enumeration

    /** Log-safe summary; never includes the serial. */
    fun summary(): String =
        when (this) {
            None -> "none"
            is Mismatch -> "filter-mismatch manufacturer=${accessory.manufacturer} model=${accessory.model}"
            is Match -> "filter-match manufacturer=${accessory.manufacturer} model=${accessory.model}"
        }
}

object Accessories {
    /** Current accessories. Android reports at most one; API failures read as none. */
    fun list(usb: UsbManager): List<UsbAccessory> =
        try {
            usb.accessoryList?.toList().orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }

    fun enumerate(usb: UsbManager, filter: AccessoryFilter): Enumeration {
        val current = list(usb)
        return current.firstOrNull(filter::matches)?.let { Enumeration.Match(it) }
            ?: current.firstOrNull()?.let { Enumeration.Mismatch(it) }
            ?: Enumeration.None
    }

    /** The enumerated instance matching [candidate], rejecting stale intent extras. */
    fun current(usb: UsbManager, candidate: UsbAccessory?): UsbAccessory? =
        candidate?.let { wanted -> list(usb).firstOrNull { same(it, wanted) } }

    /**
     * Identity comparison that avoids [UsbAccessory.equals], which can read the serial
     * and throw SecurityException before USB permission is granted. Serials are compared
     * only when both are readable. Android exposes at most one accessory at a time.
     */
    fun same(a: UsbAccessory?, b: UsbAccessory?): Boolean {
        if (a == null || b == null) return false
        if (a === b) return true
        val fieldsMatch = a.manufacturer == b.manufacturer && a.model == b.model &&
            a.description == b.description && a.version == b.version && a.uri == b.uri
        val (serialA, serialB) = readSerial(a) to readSerial(b)
        return fieldsMatch && (serialA == null || serialB == null || serialA == serialB)
    }

    private fun readSerial(accessory: UsbAccessory): String? =
        try {
            accessory.serial
        } catch (e: SecurityException) {
            null
        }

    /** Report identity. The serial is redacted; only its presence and length are kept. */
    fun describe(accessory: UsbAccessory?): String {
        if (accessory == null) return "accessory=-"
        // Reading the serial requires accessory permission on current targets.
        val serial =
            try {
                accessory.serial?.let { "redacted(${it.length} chars)" } ?: "absent"
            } catch (e: SecurityException) {
                "unreadable-without-permission"
            }
        return "accessory_manufacturer=${accessory.manufacturer}\n" +
            "accessory_model=${accessory.model}\n" +
            "accessory_version=${accessory.version}\n" +
            "accessory_description=${accessory.description}\n" +
            "accessory_uri=${accessory.uri}\n" +
            "accessory_serial=$serial"
    }
}
