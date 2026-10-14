package com.dictalab.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    private final PasswordPolicy.Validator validator=new PasswordPolicy.Validator();
    @Test void multibytePasswordCannotExceedBcryptByteLimit() {
        assertThat(validator.isValid("A1"+"ế".repeat(23),null)).isTrue();
        assertThat(validator.isValid("A1"+"ế".repeat(24),null)).isFalse();
        assertThat(validator.isValid("A1"+"x".repeat(70),null)).isTrue();
        assertThat(validator.isValid("A1"+"x".repeat(71),null)).isFalse();
    }
    @Test void samePolicyRetainsMinimumLengthAndLetterNumberRequirement() {
        assertThat(validator.isValid("Password1",null)).isTrue();
        for(String invalid:java.util.List.of("short1","password","12345678","        ","Password1\n"))
            assertThat(validator.isValid(invalid,null)).isFalse();
        assertThat(validator.isValid(null,null)).isFalse();
    }
}
