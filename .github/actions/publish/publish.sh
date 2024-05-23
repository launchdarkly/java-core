#!/bin/bash

set -ue

if $LD_RELEASE_IS_DRYRUN ; then
  echo "Doing a dry run of publishing."
else
    echo "Publishing to Sonatype"
    if [ "${LD_RELEASE_IS_PRERELEASE}" == "true" ]; then
        echo "PRERELEASE"
        ${WORKSPACE_PATH}/gradlew publishToSonatype -p ${WORKSPACE_PATH} -Psigning.keyId="${SIGNING_KEY_ID}" -Psigning.password="${SIGNING_KEY_PASSPHRASE}" -Psigning.secretKeyRingFile="${SIGNING_SECRET_KEY_RING_FILE}" -PsonatypeUsername="${SONATYPE_USER_NAME}" -PsonatypePassword="${SONATYPE_PASSWORD}" || {
            echo "Gradle publish/release failed" >&2
            exit 1
        }
    else
        echo "RELEASE"
        ${WORKSPACE_PATH}/gradlew publishToSonatype closeAndReleaseRepository -p ${WORKSPACE_PATH} -Psigning.keyId="${SIGNING_KEY_ID}" -Psigning.password="${SIGNING_KEY_PASSPHRASE}" -Psigning.secretKeyRingFile="${SIGNING_SECRET_KEY_RING_FILE}" -PsonatypeUsername="${SONATYPE_USER_NAME}" -PsonatypePassword="${SONATYPE_PASSWORD}" || {
            echo "Gradle publish/release failed" >&2
            exit 1
        }
    fi
fi
