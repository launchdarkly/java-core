# SDK contract test service

This directory contains an implementation of the cross-platform SDK testing protocol defined by https://github.com/launchdarkly/sdk-test-harness. See that project's `README` for details of this protocol, and the kinds of SDK capabilities that are relevant to the contract tests. This code should not need to be updated unless the SDK has added or removed such capabilities.

To run these tests locally, run `make contract-tests` from the SDK project root directory. This downloads the correct version of the test harness tool automatically.

Or, to test against an in-progress local version of the test harness, run `make start-contract-test-service` from the SDK project root directory; then, in the root directory of the `sdk-test-harness` project, build the test harness and run it from the command line.

The persistent data store tests need Redis on port 6379, DynamoDB Local on port 8000, and Consul on port 8500. The harness runs them only with its `-enable-persistence-tests` option. DynamoDB uses the default port of the test service, so start the service on another port, for example `make start-contract-test-service TEST_SERVICE_PORT=9000`.
