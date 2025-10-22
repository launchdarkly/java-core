package com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.request;

import java.util.HashMap;
import java.util.Map;

/*
 * #%L
 * NanoHttpd-Core
 * %%
 * Copyright (C) 2012 - 2016 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

/**
 * Represents an HTTP request method/verb. This class includes predefined instances for commonly
 * used verbs, but it is not an enum, because HTTP allows other verbs to be used as long as the
 * client and server both agree they are valid.
 */
public final class Method {
    private String name;
    
    private Method(String name) {
      this.name = name;
    }
    
    private static Map<String, Method> BUILTINS = new HashMap<>();
    private static Method makeBuiltin(String name) {
        Method m = new Method(name);
        BUILTINS.put(name, m);
        return m;
    }
    
    public static Method
        GET = makeBuiltin("GET"),
        PUT = makeBuiltin("PUT"),
        POST = makeBuiltin("POST"),
        DELETE = makeBuiltin("DELETE"),
        HEAD = makeBuiltin("HEAD"),
        OPTIONS = makeBuiltin("OPTIONS"),
        TRACE = makeBuiltin("TRACE"),
        CONNECT = makeBuiltin("CONNECT"),
        PATCH = makeBuiltin("PATCH"),
        PROPFIND = makeBuiltin("PROPFIND"),
        PROPPATCH = makeBuiltin("PROPPATCH"),
        MKCOL = makeBuiltin("MKCOL"),
        MOVE = makeBuiltin("MOVE"),
        LOCK = makeBuiltin("LOCK"),
        UNLOCK = makeBuiltin("UNLOCK"),
        NOTIFY = makeBuiltin("NOTIFY"),
        SUBSCRIBE = makeBuiltin("SUBSCRIBE");

    /**
     * Returns a Method instance for the given name, as long as it is syntactically valid. 
     * @param name the method name as a string
     * @return a Method, or null if not valid
     */
    public static Method lookup(String name) {
        if (name == null) {
            return null;
        }
        Method m = BUILTINS.get(name);
        if (m != null) {
          return m;
        }
        return isValid(name) ? new Method(name) : null;
    }
    
    private static boolean isValid(String name) {
        // allowable character set is the same as for any "token" in HTTP: no control chars or separators
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch <= ' ' || "()<>@,;:\\\"/[]?={}".contains(String.valueOf(ch))) {
              return false;
            }
        }
        return true;
    }
    
    public String name() { // for backward compatibility with code that treated this as an enum
        return name;
    }
    
    @Override
    public String toString() {
      return name;
    }
    
    @Override
    public int hashCode() {
      return name.hashCode();
    }
    
    @Override
    public boolean equals(Object other) {
      return other instanceof Method && ((Method)other).name.equals(name);
    }
}
