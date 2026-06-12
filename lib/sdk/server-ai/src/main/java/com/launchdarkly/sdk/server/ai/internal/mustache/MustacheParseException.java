// Vendored from com.samskivert:jmustache:1.15 (BSD 3-Clause, Copyright (c) 2010 Michael Bayne).
// Relocated to com.launchdarkly.sdk.server.ai.internal.mustache for supply-chain hardening (AIC-2695).
// Upstream: https://github.com/samskivert/jmustache -- unmodified except for this banner and the package
// declaration below. See THIRD-PARTY-NOTICES.txt for the full license text.
//
//
// JMustache - A Java implementation of the Mustache templating language
// http://github.com/samskivert/jmustache/blob/master/LICENSE

package com.launchdarkly.sdk.server.ai.internal.mustache;

/**
 * An exception thrown if we encounter an error while parsing a template.
 */
public class MustacheParseException extends MustacheException
{
    public MustacheParseException (String message) {
        super(message);
    }

    public MustacheParseException (String message, int lineNo) {
        super(message + " @ line " + lineNo);
    }
}
