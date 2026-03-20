package com.otasdk

import com.facebook.react.bridge.ReactApplicationContext

class OtaSdkModule(reactContext: ReactApplicationContext) :
  NativeOtaSdkSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeOtaSdkSpec.NAME
  }
}
