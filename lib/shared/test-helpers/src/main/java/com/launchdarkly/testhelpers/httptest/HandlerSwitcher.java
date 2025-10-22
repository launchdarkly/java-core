package com.launchdarkly.testhelpers.httptest;

/**
 * A delegator that forwards requests to another handler, which can be changed at any time.
 */
public final class HandlerSwitcher implements Handler {
  private volatile Handler target;
  
  /**
   * Creates an instance with an initial target.
   * 
   * @param target the handler to delegate to initially
   */
  public HandlerSwitcher(Handler target) {
    this.target = target;
  }
  
  @Override
  public void apply(RequestContext context) {
    target.apply(context);
  }
  
  /**
   * Returns the current handler that will receive requests.
   * 
   * @return the current target
   */
  public Handler getTarget() {
    return target;
  }
  
  /**
   * Changes the handler that will receive requests.
   * 
   * @param target the new target
   */
  public void setTarget(Handler target) {
    this.target = target;
  }
}
