package com.example.blap.chat

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

data class PhoneNumberParts(val countryCode: String, val nationalNumber: String) {
    fun combined(): String = if (nationalNumber.isBlank()) "" else "+${countryCode.filter(Char::isDigit)}${nationalNumber.filter(Char::isDigit)}"

    companion object {
        fun from(number: String, region: String = Locale.getDefault().country): PhoneNumberParts {
            val utility = PhoneNumberUtil.getInstance()
            val defaultCode = utility.getCountryCodeForRegion(region).takeIf { it > 0 } ?: 61
            if (number.isBlank()) return PhoneNumberParts(defaultCode.toString(), "")
            return runCatching {
                val parsed = utility.parse(number, region)
                PhoneNumberParts(parsed.countryCode.toString(), utility.getNationalSignificantNumber(parsed))
            }.getOrElse { PhoneNumberParts(defaultCode.toString(), number.filter(Char::isDigit)) }
        }
    }
}
