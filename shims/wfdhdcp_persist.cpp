// SPDX-License-Identifier: Apache-2.0

#include <android/binder_manager.h>

// The vendor service destroys a mutex before its shutdown cleanup uses it.
// Keep the lazy service registered after its first client disconnects.
__attribute__((constructor)) static void keepWfdHdcpServiceAlive() {
    AServiceManager_forceLazyServicesPersist(true);
}
