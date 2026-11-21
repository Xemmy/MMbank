package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleSenderValidationTest {
    @Test fun blankSenderIsValid() {
        assertNull(RuleCompiler.validateSender(""))
        assertNull(RuleCompiler.validateSender("   "))
    }

    @Test fun validSenderIsAccepted() = assertNull(RuleCompiler.validateSender("^(ADCB|FAB)$"))

    @Test fun invalidSenderIsReported() = assertNotNull(RuleCompiler.validateSender("(unclosed"))
}
