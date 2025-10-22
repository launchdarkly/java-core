package com.launchdarkly.testhelpers.httptest;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A delegator that provides simple request path/method matching.
 * <p>
 * The request is sent to the handler for the first matching path. If there is no matching path, it
 * returns a 404. If there is a matching path but only for a different HTTP method, it returns a 405.
 */
public final class SimpleRouter implements Handler {
  private final List<Route> routes = new ArrayList<>();
  
  private static class Route {
    final String method;
    final Pattern pattern;
    final Handler handler;
    
    Route(String method, Pattern pattern, Handler handler) {
      this.method = method;
      this.pattern = pattern;
      this.handler = handler;
    }
  }
  
  @Override
  public void apply(RequestContext context) {
    boolean matchedPath = false;
    for (Route r: routes) {
      Matcher m = r.pattern.matcher(context.getRequest().getPath());
      if (m.matches()) {
        matchedPath = true;
        if (r.method != null && !r.method.equalsIgnoreCase(context.getRequest().getMethod())) {
          continue;
        }
        if (m.groupCount() > 0) {
          ImmutableList.Builder<String> params = ImmutableList.builder();
          for (int i = 1; i <= m.groupCount(); i++) {
            params.add(m.group(i));
          }
          context = new RequestContextWithPathParams(context, params.build());
        }
        r.handler.apply(context);
        return;
      }
    }
    context.setStatus(matchedPath ? 405 : 404);
  }
  
  /**
   * Adds an exact-match path.
   * 
   * @param path the desired path
   * @param handler the handler to call for a matching request
   * @return the same instance
   */
  public SimpleRouter add(String path, Handler handler) {
    return add(null, path, handler);
  }

  /**
   * Adds an exact-match path, specifying the HTTP method.
   * 
   * @param method the desired method
   * @param path the desired path
   * @param handler the handler to call for a matching request
   * @return the same instance
   */
  public SimpleRouter add(String method, String path, Handler handler) {
    return addRegex(method, Pattern.compile(Pattern.quote(path)), handler);
  }
  
  /**
   * Adds a regex path pattern.
   * <p>
   * The regex must match the entire path. If it contains any capture groups, the matched groups
   * will be available from {@link RequestContext#getPathParam(int)}. 
   * 
   * @param regex the regex to match
   * @param handler the handler to call for a matching request
   * @return the same instance
   */
  public SimpleRouter addRegex(Pattern regex, Handler handler) {
    return addRegex(null, regex, handler);
  }
  
  /**
   * Adds a regex path pattern, speifying the HTTP method.
   * <p>
   * The regex must match the entire path. If it contains any capture groups, the matched groups
   * will be available from {@link RequestContext#getPathParam(int)}. 
   * 
   * @param method the desired method
   * @param regex the regex to match
   * @param handler the handler to call for a matching request
   * @return the same instance
   */
  public SimpleRouter addRegex(String method, Pattern regex, Handler handler) {
    routes.add(new Route(method, regex, handler));
    return this;
  }
  
  private static final class RequestContextWithPathParams implements RequestContext {
    private final RequestContext wrapped;
    private final ImmutableList<String> pathParams;
    
    RequestContextWithPathParams(RequestContext wrapped, ImmutableList<String> pathParams) {
      this.wrapped = wrapped;
      this.pathParams = pathParams;
    }

    @Override
    public RequestInfo getRequest() {
      return wrapped.getRequest();
    }

    @Override
    public void setStatus(int status) {
      wrapped.setStatus(status);
    }

    @Override
    public void setHeader(String name, String value) {
      wrapped.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
      wrapped.addHeader(name, value); 
    }

    @Override
    public void setChunked() {
      wrapped.setChunked();
    }

    @Override
    public void write(byte[] data) {
      wrapped.write(data);
    }

    @Override
    public String getPathParam(int i) {
      return i < 0 || i >= pathParams.size() ? null : pathParams.get(i);
    }
  }
}
