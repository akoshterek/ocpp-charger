package com.thenewmotion.chargenetwork.ocpp.charger

import com.thenewmotion.ocpp.messages.ChargePointErrorCode

/**
 * @author Yaroslav Klymko
 */
object ErrorCodes {

  private val codes: List[ChargePointErrorCode] = ChargePointErrorCode.values.toList

  def apply(): Stream[ChargePointErrorCode] = codes.toStream #::: apply()
}