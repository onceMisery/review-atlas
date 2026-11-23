package com.review.atlas;

import com.intellij.ui.jcef.JBCefApp;

/** Single entry point for JCEF availability: 2026.3+ splits JCEF into its own module, so class
 *  visibility failures must degrade to external-browser mode instead of crashing the tool window. */
final class AtlasJcef {

    private AtlasJcef() {
    }

    static boolean isSupported() {
        try {
            return JBCefApp.isSupported();
        } catch (Throwable t) {
            System.err.println("[Review Atlas] JCEF unavailable: " + t);
            return false;
        }
    }
}
