/*
 * Copyright (c) 2015-present, Parse, LLC.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.parse;

import java.util.HashMap;
import java.util.Map;

import bolts.Task;

/*** package */ class ParseAuthenticationController {

  private Map<String, ParseAuthenticationProvider> authenticationProviders = new HashMap<>();

  public void register(ParseAuthenticationProvider provider) {
    String authType = provider.getAuthType();
    authenticationProviders.put(provider.getAuthType(), provider);

    if (provider instanceof AnonymousAuthenticationProvider) {
      // There's nothing to synchronize
      return;
    }

    // Synchronize the current user with the auth provider.
    //TODO (grantland): Possible disk I/O on main thread
    ParseUser user = ParseUser.getCurrentUser();
    if (user != null) {
      user.synchronizeAuthData(authType);
    }
  }

  /* package for tests */ void unregister(ParseAuthenticationProvider provider) {

  }

  public boolean restoreAuthentication(String authType, Map<String, String> authData) {
    ParseAuthenticationProvider provider = authenticationProviders.get(authType);
    return provider == null || provider.restoreAuthentication(authData);
  }

  public Task<Void> deauthenticateAsync(String authType) {
    ParseAuthenticationProvider provider = authenticationProviders.get(authType);
    if (provider != null) {
      return provider.deauthenticateAsync();
    }
    return Task.forResult(null);
  }
}
