
build:
	./gradlew jar

clean:
	./gradlew clean

test:
	./gradlew test

TEMP_TEST_OUTPUT=/tmp/sdk-test-service.log
TEST_SERVICE_PORT ?= 8000
SUPPRESSION_FILE=contract-tests/test-suppressions.txt
SUPPRESSION_FILE_FDV2=contract-tests/test-suppressions-fdv2.txt

# Add any extra sdk-test-harness parameters here, such as -skip for tests that are
# temporarily not working.
TEST_HARNESS_PARAMS=

build-contract-tests:
	@cd contract-tests && ../gradlew installDist

start-contract-test-service:
	@PORT=$(TEST_SERVICE_PORT) contract-tests/service/build/install/service/bin/service

start-contract-test-service-bg:
	@echo "Test service output will be captured in $(TEMP_TEST_OUTPUT)"
	@make start-contract-test-service >$(TEMP_TEST_OUTPUT) 2>&1 &

run-contract-tests:
	@echo "Running SDK contract test v2..."
	@curl -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v2/downloader/run.sh \
      | VERSION=v2 PARAMS="-url http://localhost:$(TEST_SERVICE_PORT) -debug -skip-from=$(SUPPRESSION_FILE) $(TEST_HARNESS_PARAMS)" sh
	@echo "Running SDK contract test v3.0.0-alpha.1..."
	@curl -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v3.0.0-alpha.1/downloader/run.sh \
      | VERSION=v3.0.0-alpha.1 PARAMS="-url http://localhost:$(TEST_SERVICE_PORT) -debug -stop-service-at-end -skip-from=$(SUPPRESSION_FILE_FDV2) $(TEST_HARNESS_PARAMS)" sh

contract-tests: build-contract-tests start-contract-test-service-bg run-contract-tests

.PHONY: build-contract-tests start-contract-test-service start-contract-test-service-bg run-contract-tests contract-tests
