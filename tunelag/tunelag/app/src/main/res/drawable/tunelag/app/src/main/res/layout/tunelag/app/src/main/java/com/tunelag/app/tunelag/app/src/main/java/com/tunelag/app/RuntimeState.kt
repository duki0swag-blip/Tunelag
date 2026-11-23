package com.tunelag.app

/** Estado volátil compartido entre el servicio y la pantalla. */
object RuntimeState {
    @Volatile var tunnelUp = false
    @Volatile var lagActive = false
    @Volatile var foundEndpoints = 0
}
