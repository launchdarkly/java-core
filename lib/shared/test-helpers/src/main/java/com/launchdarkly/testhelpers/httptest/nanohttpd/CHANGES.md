# Changes Made to nanohttpd Source Code

This directory contains source code from the nanohttpd project (https://github.com/launchdarkly-labs/nanohttpd) that has been modified for embedding into this project.

The project was originally forked from: https://github.com/NanoHttpd/nanohttpd
The initial fork was in order to allow for any HTTP verb to be used instead of an enumerated list supported by nanohttpd.

This package has now been vendored as it was only used by this test-helpers project and only a subset of the entire project was required.

## Modifications

### Package Namespace Change
- Changed all package declarations from `org.nanohttpd.*` to `com.launchdarkly.testhelpers.httptest.nanohttpd.*`
- Updated all import statements to reference the new package namespace

## Original Source
- Repository: https://github.com/NanoHttpd/nanohttpd
- License: BSD 3-Clause (see LICENSE.md in this directory)
- All original copyright notices and license headers have been preserved in source files
